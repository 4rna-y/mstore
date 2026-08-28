package io.github.mstore;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 標準出力は監視対象のサーバーと共有している。どの行が mstore のものか分かるように
 * すべて {@code [HH:mm:ss mstore]} を頭に付ける。
 */
final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {
    }

    static void info(String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + " mstore] " + message);
    }
}
