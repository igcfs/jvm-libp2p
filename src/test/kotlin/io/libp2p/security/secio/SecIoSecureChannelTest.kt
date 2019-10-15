package io.libp2p.security.secio

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.multistream.Negotiator
import io.libp2p.multistream.ProtocolSelect
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestChannel.Companion.interConnect
import io.libp2p.tools.TestHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ResourceLeakDetector
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Created by Anton Nashatyrev on 19.06.2019.
 */

class SecIoSecureChannelTest {
    init {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    @Test
    fun secioInterconnect() {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)

        val latch = CountDownLatch(2)

        val protocolSelect1 = ProtocolSelect(listOf(SecIoSecureChannel(privKey1)))
        val protocolSelect2 = ProtocolSelect(listOf(SecIoSecureChannel(privKey2)))

        val eCh1 = TestChannel("#1", true, LoggingHandler("#1", LogLevel.ERROR),
            Negotiator.createRequesterInitializer("/secio/1.0.0"),
            protocolSelect1)

        val eCh2 = TestChannel("#2", false,
            LoggingHandler("#2", LogLevel.ERROR),
            Negotiator.createResponderInitializer(listOf(ProtocolMatcher(Mode.STRICT, "/secio/1.0.0"))),
            protocolSelect2)

        println("Connecting channels...")
        interConnect(eCh1, eCh2)

        println("Waiting for secio negotiation to complete...")
        protocolSelect1.selectedFuture.get(5, TimeUnit.SECONDS)
        protocolSelect2.selectedFuture.get(5, TimeUnit.SECONDS)
        println("Secured!")

        val handler1 = SecioTestHandler("1", latch)
        val handler2 = SecioTestHandler("2", latch)

        eCh1.pipeline().addLast(handler1)
        eCh2.pipeline().addLast(handler2)

        eCh1.pipeline().fireChannelActive()
        eCh2.pipeline().fireChannelActive()

        latch.await(10, TimeUnit.SECONDS)

        Assertions.assertEquals("Hello World from 1", handler2.received)
        Assertions.assertEquals("Hello World from 2", handler1.received)

        System.gc()
        Thread.sleep(500)
        System.gc()
        Thread.sleep(500)
        System.gc()
    }

    class SecioTestHandler(name: String, val latch: CountDownLatch): TestHandler(name) {
        lateinit var received : String

        override fun channelActive(ctx: ChannelHandlerContext) {
            super.channelActive(ctx)
            ctx.writeAndFlush("Hello World from $name".toByteArray().toByteBuf())
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
            msg as ByteBuf
            received = msg.toByteArray().toString(StandardCharsets.UTF_8)
            logger.debug("==$name== read: $received")
            latch.countDown()
        }
    }

    companion object {
        private val logger = LogManager.getLogger(SecIoSecureChannelTest::class.java)
    }
}
