package info.dvkr.screenstream.data.presenter.foreground


import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.data.image.ImageGenerator
import info.dvkr.screenstream.data.image.ImageGeneratorImpl
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.httpserver.HttpServer
import info.dvkr.screenstream.domain.httpserver.HttpServerImpl
import info.dvkr.screenstream.domain.settings.Settings
import info.dvkr.screenstream.domain.utils.Utils
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.SubscriptionReceiveChannel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch
import rx.functions.Action1
import timber.log.Timber
import kotlin.coroutines.experimental.CoroutineContext

open class FgPresenter(private val crtContext: CoroutineContext,
                       private val eventBus: EventBus,
                       private val globalStatus: GlobalStatus,
                       private val settings: Settings,
                       private val jpegBytesStream: BehaviorRelay<ByteArray>) {

    private val random = java.util.Random()

    private var httpServer: HttpServer? = null
    private var imageGenerator: ImageGenerator? = null
    private val slowConnections: MutableList<HttpServer.Client> = ArrayList()


    private var viewChannel: SendChannel<FgView.FromEvent>
    private lateinit var subscription: SubscriptionReceiveChannel<EventBus.GlobalEvent>
    private var view: FgView? = null

    init {
        Timber.i("[${Utils.getLogPrefix(this)}] Init")

        viewChannel = actor(crtContext, Channel.UNLIMITED) {
            for (fromEvent in this) when (fromEvent) {
                is FgView.FromEvent.Init -> {
                    if (settings.enablePin && settings.newPinOnAppStart) {
                        val newPin = randomPin()
                        settings.currentPin = newPin
                        eventBus.send(EventBus.GlobalEvent.SetPin(newPin))
                    }

                    notifyView(FgView.ToEvent.StartHttpServer)
                }

                is FgView.FromEvent.StartHttpServer -> {
                    val (serverAddress, favicon, logo, baseIndexHtml, basePinRequestHtml, pinRequestErrorMsg, jpegByteStream) = fromEvent
                    globalStatus.error.set(null)
                    httpServer = HttpServerImpl(serverAddress,
                            favicon,
                            logo,
                            baseIndexHtml,
                            settings.htmlBackColor,
                            settings.disableMJPEGCheck,
                            settings.enablePin,
                            settings.currentPin,
                            basePinRequestHtml,
                            pinRequestErrorMsg,
                            jpegByteStream,
                            eventBus,
                            crtContext,
                            Action1 { Timber.v(it) })

                    notifyView(FgView.ToEvent.NotifyImage(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is FgView.FromEvent.StopHttpServer -> {
                    httpServer?.stop()
                    httpServer = null
                }

                is FgView.FromEvent.StartImageGenerator -> {
                    imageGenerator = ImageGeneratorImpl(fromEvent.display, fromEvent.mediaProjection) { action ->
                        launch(crtContext) {
                            when (action) {
                                is ImageGenerator.ImageGeneratorEvent.OnError -> {
                                    eventBus.send(EventBus.GlobalEvent.Error(action.error))
                                    Timber.e(action.error, "ImageGenerator: ERROR")
                                }

                                is ImageGenerator.ImageGeneratorEvent.OnJpegImage -> {
                                    jpegBytesStream.call(action.image)
                                }
                            }
                        }
                    }.apply {
                        setImageResizeFactor(settings.resizeFactor)
                        setImageJpegQuality(settings.jpegQuality)
                        start()
                    }

                    globalStatus.isStreamRunning.set(true)
                    eventBus.send(EventBus.GlobalEvent.StreamStatus)
                }

                is FgView.FromEvent.StopStreamComplete -> {
                    if (settings.enablePin && settings.autoChangePin) {
                        val newPin = randomPin()
                        settings.currentPin = newPin
                        eventBus.send(EventBus.GlobalEvent.SetPin(newPin))
                        eventBus.send(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_RELOAD_PAGE))
                    }
                    notifyView(FgView.ToEvent.NotifyImage(ImageNotify.IMAGE_TYPE_DEFAULT))
                }

                is FgView.FromEvent.HttpServerRestartRequest -> {
                    eventBus.send(EventBus.GlobalEvent.HttpServerRestart(ImageNotify.IMAGE_TYPE_NONE))
                }

                is FgView.FromEvent.ScreenOff -> {
                    if (settings.stopOnSleep && globalStatus.isStreamRunning.get())
                        notifyView(FgView.ToEvent.StopStream())
                }

                is FgView.FromEvent.CurrentInterfaces -> {
                    eventBus.send(EventBus.GlobalEvent.CurrentInterfaces(fromEvent.interfaceList))
                }
            }
        }
    }

    fun offer(fromEvent: FgView.FromEvent) {
        Timber.d("[${Utils.getLogPrefix(this)}] fromEvent: ${fromEvent.javaClass.simpleName}")
        viewChannel.offer(fromEvent)
    }

    private fun notifyView(toEvent: FgView.ToEvent, timeout: Long = 0) = view?.toEvent(toEvent, timeout)

    fun attach(newView: FgView) {
        Timber.i("[${Utils.getLogPrefix(this)}] Attach")
        view?.let { detach() }
        view = newView

        subscription = eventBus.openSubscription()
        launch(crtContext) {
            subscription.consumeEach { globalEvent ->
                Timber.d("[${Utils.getLogPrefix(this)}] globalEvent: ${globalEvent.javaClass.simpleName}")

                when (globalEvent) {
                // From FgPresenter, StartPresenter & ProjectionCallback
                    is EventBus.GlobalEvent.StopStream ->
                        if (globalStatus.isStreamRunning.get()) {
                            imageGenerator?.stop()
                            imageGenerator = null
                            globalStatus.isStreamRunning.set(false)
                            eventBus.send(EventBus.GlobalEvent.StreamStatus)
                            notifyView(FgView.ToEvent.StopStream())
                        }

                // From StartPresenter
                    is EventBus.GlobalEvent.AppExit -> {
                        notifyView(FgView.ToEvent.AppExit)
                    }

                // From SettingsPresenter, FgPresenter
                    is EventBus.GlobalEvent.HttpServerRestart -> {
                        globalStatus.error.set(null)
                        val restartReason = globalEvent.reason
                        if (globalStatus.isStreamRunning.get()) {
                            imageGenerator?.stop()
                            imageGenerator = null
                            globalStatus.isStreamRunning.set(false)
                            eventBus.send(EventBus.GlobalEvent.StreamStatus)
                            notifyView(FgView.ToEvent.StopStream(false))
                        }

                        notifyView(FgView.ToEvent.NotifyImage(restartReason))
                        notifyView(FgView.ToEvent.StartHttpServer, 1000)
                    }

                // From StartPresenter
                    is EventBus.GlobalEvent.CurrentInterfacesRequest -> {
                        notifyView(FgView.ToEvent.CurrentInterfacesRequest)
                    }

                // From HttpServer & ImageGenerator
                    is EventBus.GlobalEvent.Error -> {
                        if (globalStatus.isStreamRunning.get()) {
                            imageGenerator?.stop()
                            imageGenerator = null
                            globalStatus.isStreamRunning.set(false)
                            eventBus.send(EventBus.GlobalEvent.StreamStatus)
                            notifyView(FgView.ToEvent.StopStream(false))
                        }

                        notifyView(FgView.ToEvent.Error(globalEvent.error))
                    }

                // From HttpServerImpl
                    is EventBus.GlobalEvent.CurrentClients -> {
                        val currentSlowConnections = globalEvent.clientsList.filter { it.hasBackpressure }.toList()
                        if (!slowConnections.containsAll(currentSlowConnections))
                            notifyView(FgView.ToEvent.SlowConnectionDetected)
                        slowConnections.clear()
                        slowConnections.addAll(currentSlowConnections)
                    }

                // From SettingsPresenter
                    is EventBus.GlobalEvent.ResizeFactor -> {
                        imageGenerator?.setImageResizeFactor(globalEvent.value)
                    }

                // From SettingsPresenter
                    is EventBus.GlobalEvent.JpegQuality -> {
                        imageGenerator?.setImageJpegQuality(globalEvent.value)
                    }
                }
            }
        }
    }

    fun detach() {
        Timber.i("[${Utils.getLogPrefix(this)}] Detach")
        subscription.close()
        view = null
    }

    private fun randomPin(): String =
            Integer.toString(random.nextInt(10)) + Integer.toString(random.nextInt(10)) +
                    Integer.toString(random.nextInt(10)) + Integer.toString(random.nextInt(10))
}