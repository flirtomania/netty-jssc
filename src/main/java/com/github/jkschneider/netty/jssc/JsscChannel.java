package com.github.jkschneider.netty.jssc;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.oio.OioByteStreamChannel;
import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static com.github.jkschneider.netty.jssc.JsscChannelOption.*;

/**
 * A channel to a serial device using the Jssc library.
 */
public class JsscChannel extends OioByteStreamChannel {

    private static final Logger log = LoggerFactory.getLogger(JsscChannel.class);

    private static final JsscDeviceAddress LOCAL_ADDRESS = new JsscDeviceAddress("localhost");

    private final JsscChannelConfig config;

    private boolean open = true;

    private JsscDeviceAddress deviceAddress;
    private SerialPort serialPort;

    public JsscChannel() {
        super(null);
        config = new DefaultJsscChannelConfig(this);
    }

    @Override
    public JsscChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new JsscUnsafe();
    }

    /**
     * this is important code
     * if you remove it channel will end up in deadlock
     * fixme possible locking issues see isReadPending()
     *
     * @param buf
     * @return
     * @throws Exception
     */
    @Override
    protected int doReadBytes(ByteBuf buf) throws Exception {
        if (!open) {
            return -1;
        }
        if (available() <= 0) {
            // throttle to reduce cpu
            //Thread.sleep(1);
            //Thread.sleep(0, 100_000);
            LockSupport.parkNanos(100_000);
            return 0;
        }
        int read = super.doReadBytes(buf);
        log.trace("read {}", read);
        return read;
    }

    @Override
    protected boolean isInputShutdown() {
        return !open;
    }

    @Override
    protected ChannelFuture shutdownInput() {
        return newFailedFuture(new UnsupportedOperationException("shutdownInput"));
    }

    @Override
    protected void doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        JsscDeviceAddress remote = (JsscDeviceAddress) remoteAddress;
        serialPort = new SerialPort(remote.value());
        deviceAddress = remote;
        log.trace("Opening port {}", remote.value());
        serialPort.openPort();
    }

    protected void doInit() throws Exception {
        log.trace("Setting params");
        serialPort.setParams(
                config().getOption(BAUD_RATE),
                config().getOption(DATA_BITS),
                config().getOption(STOP_BITS),
                config().getOption(PARITY_BIT),
                config().getOption(RTS),
                config().getOption(DTR)
        );

        final PipedOutputStream writeStream = new PipedOutputStream();
        // increased default size to 16k, because default size
        PipedInputStream readStream = new PipedInputStream(writeStream, 8192 * 2);

        serialPort.setEventsMask(SerialPort.MASK_RXCHAR);
        serialPort.addEventListener(new SerialPortEventListener() {
            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.isRXCHAR()) {
                    log.trace("rxchar:{}", event.getEventValue());
                    try {
                        byte[] bytes = serialPort.readBytes(event.getEventValue());
                        log.trace("rxchar read:{}", bytes.length);
                        writeStream.write(bytes);
                        writeStream.flush();
                    } catch (IOException | SerialPortException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });

        activate(readStream, jsscOutputStream);
    }

    @Override
    public JsscDeviceAddress localAddress() {
        return (JsscDeviceAddress) super.localAddress();
    }

    @Override
    public JsscDeviceAddress remoteAddress() {
        return (JsscDeviceAddress) super.remoteAddress();
    }

    @Override
    protected JsscDeviceAddress localAddress0() {
        return LOCAL_ADDRESS;
    }

    @Override
    protected JsscDeviceAddress remoteAddress0() {
        return deviceAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        open = false;
        try {
            super.doClose();
        } finally {
            log.trace("Closing port");
            if (serialPort != null) {
                serialPort.closePort();
                serialPort = null;
            }
        }
    }

    private final OutputStream jsscOutputStream = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            try {
                serialPort.writeInt(b);
            } catch (SerialPortException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void write(byte[] b) throws IOException {
            try {
                serialPort.writeBytes(b);
            } catch (SerialPortException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byte[] partialB = new byte[len];
            System.arraycopy(b, off, partialB, 0, len);
            write(partialB);
        }
    };

    private final class JsscUnsafe extends AbstractUnsafe {
        @Override
        public void connect(final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                final boolean wasActive = isActive();
                doConnect(remoteAddress, localAddress);

                int waitTime = config().getOption(WAIT_TIME);
                if (waitTime > 0) {
                    eventLoop().schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                doInit();
                                safeSetSuccess(promise);
                                if (!wasActive && isActive()) {
                                    pipeline().fireChannelActive();
                                }
                            } catch (Throwable t) {
                                safeSetFailure(promise, t);
                                closeIfClosed();
                            }
                        }
                    }, waitTime, TimeUnit.MILLISECONDS);
                } else {
                    doInit();
                    safeSetSuccess(promise);
                    if (!wasActive && isActive()) {
                        pipeline().fireChannelActive();
                    }
                }
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
            }
        }
    }
}
