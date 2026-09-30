package consumer
import android.content.Context
import io.github.gycrosskit.diagnostics.*
fun androidStore(context: Context) = androidDiagnosticStore(context)
fun androidRecorder(store: DiagnosticStore) = AndroidCrashRecorder(store)
