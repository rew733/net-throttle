package hev.htproxy

object TProxyService {
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (t: Throwable) {
            loadError = "LoadLib Error: ${t.message}"
        }
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
