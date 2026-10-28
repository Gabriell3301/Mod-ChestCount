package com.chestcount.mod.service;

import java.net.IDN;
import java.util.Locale;

/** Pure textual normalization; never performs DNS lookups or sends traffic. */
public final class ServerAddressNormalizer {
    public static String normalize(String address) {
        String s=address.trim().toLowerCase(Locale.ROOT);
        if(s.isEmpty()) throw new IllegalArgumentException("Empty server address");
        String host; String port=null;
        if(s.startsWith("[")) {
            int end=s.indexOf(']');
            if(end<0) throw new IllegalArgumentException("Invalid IPv6 server address");
            host=s.substring(0,end+1);
            if(s.length()>end+1) {
                if(s.charAt(end+1)!=':') throw new IllegalArgumentException("Invalid server port");
                port=s.substring(end+2);
            }
        } else if(s.chars().filter(c -> c==':').count()>1) {
            host="["+s+"]"; // Never truncate IPv6 at its first colon.
        } else {
            int colon=s.indexOf(':');
            host=IDN.toASCII(colon<0?s:s.substring(0,colon));
            while(host.endsWith(".")) host=host.substring(0,host.length()-1);
            if(host.isEmpty()) throw new IllegalArgumentException("Empty server host");
            if(colon>=0) port=s.substring(colon+1);
        }
        int number=port==null?25565:Integer.parseInt(port);
        if(number<1 || number>65535) throw new IllegalArgumentException("Invalid server port");
        return host+(number==25565?"":":"+number);
    }
}
