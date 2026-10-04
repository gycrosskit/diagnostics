package consumer
import io.github.gycrosskit.diagnostics.dingtalk.*
// 只做链接/类型检查，验证时不调用 send。
fun notificationTransport(webhook: String, secret: String) = DingTalkWebhookClient(webhook, secret)
fun decodeWebhookFixture() = decodeDingTalkWebhookResponse("""{"errcode":0}""")
