from pathlib import Path
app=Path('../ThroneForAndroid/app/src/main/java/io/nekohasekai/sagernet/bg/test')
src=(app/'QualityTestRunner.kt').read_text()
s=src[src.index('    private suspend fun probe('):].replace('private suspend fun probe(', 'suspend fun probe(')
s=s.replace('https://cp.cloudflare.com/generate_204','https://127.0.0.1:23101/baseline').replace('https://speed.cloudflare.com/__down?bytes=65536','https://127.0.0.1:23102/stream')
imports='\n'.join(line for line in src.splitlines() if line.startswith(('import java.', 'import okhttp3.', 'import kotlinx.coroutines.')))
prefix='''
internal object SystemClock { fun elapsedRealtimeNanos() = System.nanoTime() }
internal object DataStore { const val qualityGatewayUrl = "https://127.0.0.1:23103/gateway" }
internal class Session { var cancelled = false }
internal class QualityTestRunner(private val session: Session) {
    private var activeCall: Call? = null
'''
main='''
fun main() = kotlinx.coroutines.runBlocking {
    for ((name, port) in listOf("TUIC v5" to 23011, "Hysteria2 Brutal" to 23012,
        "Naive H2" to 23013, "Naive H3" to 23014)) {
        for (scenario in listOf("clean", "stall", "truncated", "budget")) {
            java.io.File("scenario").writeText(scenario)
            val report = QualityTestRunner(Session()).probe(port, "lab") { /* native pair is already listening */ }
            println("$name $scenario -> ${report.score}, warning=${report.gatewayWarning}")
            println(report.details)
            when (scenario) {
                "clean" -> check(report.score != null && report.gatewayWarning)
                "stall", "truncated" -> check(report.score != null && !report.gatewayWarning)
                "budget" -> check(report.score == null && !report.gatewayWarning)
            }
            kotlinx.coroutines.delay(250)
        }
    }
}
'''
Path('NetworkLab.kt').write_text(src.splitlines()[0]+'\n'+imports+'\n'+prefix+s+main)
