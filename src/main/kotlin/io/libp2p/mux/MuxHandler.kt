package io.libp2p.mux

import io.libp2p.core.Stream
import io.libp2p.transport.implementation.StreamOverNetty
import io.libp2p.core.StreamHandler
import io.libp2p.core.StreamPromise
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.STREAM
import io.libp2p.etc.types.forward
import io.libp2p.etc.util.netty.mux.AbstractMuxHandler
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

class MuxHandler(
    private val ready: CompletableFuture<StreamMuxer.Session>?
) : AbstractMuxHandler<ByteBuf>(), StreamMuxer.Session {
    private val idGenerator = AtomicLong(0xF)

    constructor(streamHandler: StreamHandler<*>) : this(null) {
        this.inboundStreamHandler = streamHandler
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        super.handlerAdded(ctx)
        ready?.complete(this)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as MuxFrame
        when (msg.flag) {
            MuxFrame.Flag.OPEN -> onRemoteOpen(msg.id)
            MuxFrame.Flag.CLOSE -> onRemoteDisconnect(msg.id)
            MuxFrame.Flag.RESET -> onRemoteClose(msg.id)
            MuxFrame.Flag.DATA -> childRead(msg.id, msg.data!!)
        }
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf): Boolean {
        getChannelHandlerContext().writeAndFlush(
            MuxFrame(
                child.id,
                MuxFrame.Flag.DATA,
                data
            )
        )
        return true
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MuxFrame(child.id, MuxFrame.Flag.OPEN))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MuxFrame(child.id, MuxFrame.Flag.CLOSE))
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MuxFrame(child.id, MuxFrame.Flag.RESET))
    }

    override fun onRemoteCreated(child: MuxChannel<ByteBuf>) {
    }

    override fun generateNextId() = MuxId(idGenerator.incrementAndGet(), true)

    override var inboundStreamHandler: StreamHandler<*>? = null
        set(value) {
            field = value
            inboundInitializer = { inboundStreamHandler!!.handleStream(createStream(it)) }
        }

    private fun createStream(channel: MuxChannel<ByteBuf>): Stream {
        val connection = ctx!!.channel().attr(CONNECTION).get()
        val stream = StreamOverNetty(channel, connection)
        channel.attr(STREAM).set(stream)
        return stream
    }

    override fun <T> createStream(streamHandler: StreamHandler<T>): StreamPromise<T> {
        val controller = CompletableFuture<T>()
        val stream = newStream { streamHandler.handleStream(createStream(it)).forward(controller) }
            .thenApply { it.attr(STREAM).get() }
        return StreamPromise(stream, controller)
    }
}