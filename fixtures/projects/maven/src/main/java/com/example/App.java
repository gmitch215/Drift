package com.example;

import com.sun.net.httpserver.HttpServer;
import java.lang.foreign.Linker;
import java.util.List;
import javax.persistence.*;
import javax.servlet.http.HttpServlet;
import javax.xml.bind.JAXBContext;
import static java.lang.Math.max;
import sun.misc.Unsafe;

public class App {
    void run() {
        System.setSecurityManager(null);
        Thread.ofVirtual().start(() -> {});
        List.of(1).getFirst();
        Math.clamp(1, 0, 2);
        Files.readString(Path.of("a"));
        Files.writeString(Path.of("a"), "x");
        Files.mismatch(Path.of("a"), Path.of("b"));
        Stream.of(1).mapMulti((i, sink) -> sink.accept(i));
        Predicate.not(String::isEmpty);
    }
}
