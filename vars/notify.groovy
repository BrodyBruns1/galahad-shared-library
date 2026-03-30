/**
 * notify — Galahad notification utilities
 *
 * Usage:
 *   notify.slack('Job done')
 *   notify.slack(channel: '#ops', message: 'Alert', color: 'danger')
 *   notify.ops('Container down: vaultwarden')
 *   notify.dashboard([type: 'ai_response', text: 'All systems green', strength: 1.5])
 *   notify.hitl('deploy-vm', 'Restart VM 103?')
 */

import org.galahad.Config

def slack(Map args) {
    slackSend(
        channel: args.channel ?: Config.SLACK_DEFAULT,
        color:   args.color   ?: 'good',
        message: args.message ?: args.msg ?: ''
    )
}

// Shorthand: notify.slack('simple message')
def slack(String message, String channel = Config.SLACK_DEFAULT, String color = 'good') {
    slackSend(channel: channel, color: color, message: message)
}

def ops(String message, String color = 'warning') {
    slackSend(channel: Config.SLACK_OPS, color: color, message: message)
}

def codex(String message, String color = 'good') {
    slackSend(channel: Config.SLACK_CODEX, color: color, message: message)
}

def research(String message) {
    slackSend(channel: Config.SLACK_RESEARCH, color: 'good', message: message)
}

def hitl(String jobName, String question, int timeoutMin = 15) {
    slackSend(
        channel: Config.SLACK_HITL,
        color: 'warning',
        message: """:warning: *Approval required* — `${jobName}`
> ${question}
_Timeout: ${timeoutMin} min — auto-aborts if no response_
Approve at: ${env.BUILD_URL}input"""
    )
}

def dashboard(Map payload) {
    try {
        httpRequest(
            url: Config.STT_PUSH_URL,
            httpMode: 'POST',
            contentType: 'APPLICATION_JSON',
            requestBody: groovy.json.JsonOutput.toJson(payload),
            validResponseCodes: '200:299',
            timeout: 10
        )
    } catch (e) {
        echo "Dashboard push failed (non-fatal): ${e.message}"
    }
}

def galahad(String text, def strength = 1.5, String intent = 'general') {
    dashboard([
        type:     'ai_response',
        text:     text,
        intent:   intent,
        source:   'jenkins',
        strength: strength
    ])
    slack(text)
}
