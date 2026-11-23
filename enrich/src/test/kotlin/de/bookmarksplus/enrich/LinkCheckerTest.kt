package de.bookmarksplus.enrich

import com.sun.net.httpserver.HttpServer
import de.bookmarksplus.data.DatabaseFactory
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkCheckerTest {

    @Test
    fun followsRedirectsAndClassifiesErrors() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun route(path: String, code: Int, location: String? = null) = server.createContext(path) { ex ->
            location?.let { ex.responseHeaders.add("Location", it) }
            ex.sendResponseHeaders(code, -1)
            ex.close()
        }
        route("/ok", 200)
        route("/old", 301, "/step")
        route("/step", 302, "/ok")
        route("/gone", 404)
        route("/loop", 302, "/loop")
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val (_, db) = DatabaseFactory.open(null)
            val c = LinkChecker(db)
            assertEquals(LinkResult(200, "$base/ok"), c.check("$base/ok"))
            assertEquals(LinkResult(200, "$base/ok"), c.check("$base/old"))
            assertEquals(404, c.check("$base/gone").status)
            assertEquals("loop", c.check("$base/loop").error)
            assertEquals(LinkResult(0, "https://does-not-exist.invalid/", "dns"), c.check("https://does-not-exist.invalid/"))
            c.close()
        } finally {
            server.stop(0)
        }
    }
}
