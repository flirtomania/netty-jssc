package com.github.jkschneider.netty.jssc;

import io.netty.channel.ChannelOption;

/**
 * Option for configuring a serial port connection
 */
public class JsscChannelOption {

    public static final ChannelOption<Integer> BAUD_RATE =
            ChannelOption.newInstance("BAUD_RATE");

    public static final ChannelOption<Boolean> DTR =
            ChannelOption.newInstance("DTR");

    public static final ChannelOption<Boolean> RTS =
            ChannelOption.newInstance("RTS");

    public static final ChannelOption<Integer> STOP_BITS =
            ChannelOption.newInstance("STOP_BITS");

    public static final ChannelOption<Integer> DATA_BITS =
            ChannelOption.newInstance("DATA_BITS");

    public static final ChannelOption<Integer> PARITY_BIT =
            ChannelOption.newInstance("PARITY_BIT");
    
    public static final ChannelOption<Integer> WAIT_TIME =
            ChannelOption.newInstance("WAIT_TIME");

}