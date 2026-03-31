/**
 * codex — Galahad Codex agent bridge
 *
 * Usage:
 *   codex.task('Refactor the dashboard JS to use ES modules')
 *   codex.guidance('append', 'Focus on the holoCore.js file only')
 *   def state = codex.status()
 *   codex.abort('Task no longer needed')
 */

import org.galahad.Config

import groovy.json.JsonOutput

def task(String prompt, String workDir = '/root/work') {
    def body = JsonOutput.toJson([
        prompt:   prompt,
        model:    Config.CODEX_MODEL,
        work_dir: workDir,
        source:   'jenkins'
    ])
    def resp = httpRequest(
        url:         "${Config.VALKEY_BRIDGE}/task",
        httpMode:    'POST',
        contentType: 'APPLICATION_JSON',
        requestBody: body,
        timeout:     15
    )
    echo "Codex task queued: ${prompt.take(80)}..."
    notify.codex(":robot_face: *New Codex task queued*\n> ${prompt.take(200)}")
    return resp.content
}

def guidance(String type, String message) {
    // type: append | pause | resume | redirect | abort
    def body = JsonOutput.toJson([type: type, message: message])
    httpRequest(
        url:         "${Config.VALKEY_BRIDGE}/guidance",
        httpMode:    'POST',
        contentType: 'APPLICATION_JSON',
        requestBody: body,
        timeout:     10
    )
    echo "Codex guidance sent [${type}]: ${message}"
}

def abort(String reason = 'Aborted by Jenkins pipeline') {
    guidance('abort', reason)
    notify.codex(":stop_sign: *Codex task aborted*\n> ${reason}", 'danger')
}

def pause(String reason = 'Paused by Jenkins') {
    guidance('pause', reason)
    notify.codex(":pause_button: *Codex task paused*\n> ${reason}", 'warning')
}

def resume(String message = '') {
    guidance('resume', message ?: 'Resumed by Jenkins')
    notify.codex(":arrow_forward: *Codex task resumed*")
}

def status() {
    def raw = homelab.sshProxmox("""
VALKEY_PASS=\$(cat /root/.claude/secrets/valkey_password) python3 - <<'PY'
import json
import os
import redis

r = redis.Redis(
    host='10.201.52.171',
    port=6379,
    password=os.environ['VALKEY_PASS'],
    decode_responses=True,
)
state = r.hgetall('codex:task:current')
heartbeat = r.get('codex:heartbeat')
queue_len = r.llen('codex:task:queue')
print(json.dumps({'state': state, 'heartbeat': heartbeat, 'queue': queue_len}))
PY
""".trim())
    def jsonLine = raw.split('\n').find { it.trim().startsWith('{') }
    return new groovy.json.JsonSlurperClassic().parseText(jsonLine ?: '{"state":{},"heartbeat":null,"queue":0}')
}

def history(int limit = 1) {
    def safeLimit = Math.max(1, limit)
    def raw = homelab.sshProxmox("""
VALKEY_PASS=\$(cat /root/.claude/secrets/valkey_password) python3 - <<'PY'
import json
import os
import redis

r = redis.Redis(
    host='10.201.52.171',
    port=6379,
    password=os.environ['VALKEY_PASS'],
    decode_responses=True,
)
items = []
for item in r.lrange('codex:task:history', 0, ${safeLimit - 1}):
    try:
        items.append(json.loads(item))
    except json.JSONDecodeError:
        items.append({'raw': item})
print(json.dumps(items))
PY
""".trim())
    def jsonLine = raw.split('\n').find { it.trim().startsWith('[') }
    return new groovy.json.JsonSlurperClassic().parseText(jsonLine ?: '[]')
}

def waitForCompletion(int pollSeconds = 30, int timeoutMin = 60) {
    def deadline = System.currentTimeMillis() + (timeoutMin * 60 * 1000)
    def lastStatus = ''

    while (System.currentTimeMillis() < deadline) {
        def s = status()
        def currentStatus = s.state?.status ?: 'unknown'

        if (currentStatus != lastStatus) {
            echo "Codex status: ${currentStatus}"
            lastStatus = currentStatus
        }

        if (currentStatus in ['completed', 'failed', 'aborted']) {
            return s.state
        }

        sleep(pollSeconds)
    }

    echo "Codex task timed out after ${timeoutMin} min"
    return null
}
