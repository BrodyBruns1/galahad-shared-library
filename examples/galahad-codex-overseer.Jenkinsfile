@Library('galahad') _

import groovy.json.JsonOutput

def readTaskSnapshot(String taskId) {
    def resp = httpRequest(
        url: "http://10.201.52.200:7379/task_state?task_id=${taskId}&format=env",
        httpMode: 'GET',
        validResponseCodes: '200',
        timeout: 15
    )
    def data = [:]
    resp.content.split('\n').each { line ->
        if (line.contains('=')) {
            def parts = line.split('=', 2)
            data[parts[0]] = parts[1]
        }
    }
    return data
}

def sendGuidance(String type, String message) {
    httpRequest(
        url: 'http://10.201.52.200:7379/guidance',
        httpMode: 'POST',
        contentType: 'APPLICATION_JSON',
        requestBody: JsonOutput.toJson([type: type, message: message]),
        validResponseCodes: '200:299',
        timeout: 15
    )
}

def extractTaskId(String body) {
    def marker = '"task_id":'
    def start = body.indexOf(marker)
    if (start < 0) {
        return ''
    }
    def firstQuote = body.indexOf('"', start + marker.length())
    if (firstQuote < 0) {
        return ''
    }
    def secondQuote = body.indexOf('"', firstQuote + 1)
    if (secondQuote < 0) {
        return ''
    }
    return body.substring(firstQuote + 1, secondQuote)
}

pipeline {
    agent any

    parameters {
        string(name: 'PROMPT', defaultValue: 'Write galahad-overseer-test into /root/work/galahad-overseer-test.txt', description: 'Codex task prompt')
        string(name: 'WORK_DIR', defaultValue: '/root/work', description: 'Working directory for Codex')
        choice(name: 'GUIDANCE_MODE', choices: ['notify_only', 'auto_append'], description: 'Whether Galahad may append guidance automatically')
        string(name: 'POLL_SECONDS', defaultValue: '20', description: 'Polling interval in seconds')
        string(name: 'TIMEOUT_MIN', defaultValue: '60', description: 'Timeout in minutes')
    }

    stages {
        stage('Queue Task') {
            steps {
                script {
                    def resp = httpRequest(
                        url: 'http://10.201.52.200:7379/task',
                        httpMode: 'POST',
                        contentType: 'APPLICATION_JSON',
                        requestBody: JsonOutput.toJson([
                            prompt: params.PROMPT,
                            model: 'gpt-5.4',
                            work_dir: params.WORK_DIR,
                            source: "jenkins:${env.JOB_NAME}",
                        ]),
                        validResponseCodes: '200:299',
                        timeout: 15
                    )
                    env.CODEX_TASK_ID = extractTaskId(resp.content)
                    currentBuild.description = env.CODEX_TASK_ID
                    notify.codex(""":robot_face: *Galahad queued a Codex task*
Task: `${env.CODEX_TASK_ID}`
Prompt: ${params.PROMPT.take(220)}""")
                }
            }
        }

        stage('Oversee') {
            steps {
                script {
                    long deadline = System.currentTimeMillis() + (params.TIMEOUT_MIN.toInteger() * 60L * 1000L)
                    String lastObservedStatus = ''
                    String lastGuidance = ''
                    String lastLine = ''
                    int stalledPolls = 0
                    Map terminalHistory = null

                    while (System.currentTimeMillis() < deadline) {
                        def snap = readTaskSnapshot(env.CODEX_TASK_ID)
                        def currentIsTarget = snap.CURRENT_IS_TARGET == 'true'
                        def historyFound = snap.HISTORY_FOUND == 'true'
                        def status = historyFound ? (snap.HISTORY_STATUS ?: 'unknown') : (currentIsTarget ? (snap.CURRENT_STATUS ?: 'unknown') : 'queued')
                        def lastLineNow = currentIsTarget ? (snap.CURRENT_LAST_LINE ?: '') : ''

                        if (status != lastObservedStatus) {
                            notify.codex(""":satellite: *Codex status update*
Task: `${env.CODEX_TASK_ID}`
Status: `${status}`
Queue: ${snap.QUEUE ?: '0'}
Heartbeat: ${snap.HEARTBEAT ?: 'missing'}""", status == 'failed' ? 'danger' : 'good')
                            lastObservedStatus = status
                        }

                        if (historyFound) {
                            terminalHistory = [
                                status: snap.HISTORY_STATUS ?: 'unknown',
                                returncode: snap.HISTORY_RETURNCODE ?: '',
                            ]
                            break
                        }

                        if (currentIsTarget && status == 'running') {
                            if (lastLineNow == lastLine) {
                                stalledPolls++
                            } else {
                                stalledPolls = 0
                                lastLine = lastLineNow
                            }

                            if (params.GUIDANCE_MODE == 'auto_append' && stalledPolls >= 6) {
                                def advice = llm.ollama("""You are Galahad overseeing a Codex coding task.

Task prompt:
${params.PROMPT}

Current status: ${status}
Last event line:
${lastLineNow ?: '(none yet)'}

If no intervention is needed, reply exactly KEEP_GOING.
If you do recommend intervention, reply with one short actionable sentence to append as guidance.""")?.trim()

                                if (advice && advice != 'KEEP_GOING' && advice != lastGuidance) {
                                    sendGuidance('append', advice)
                                    notify.codex(":compass: *Galahad appended guidance*\n> ${advice}", 'warning')
                                    lastGuidance = advice
                                    stalledPolls = 0
                                }
                            }
                        }

                        sleep(params.POLL_SECONDS.toInteger())
                    }

                    if (!terminalHistory) {
                        error("Timed out waiting for Codex task ${env.CODEX_TASK_ID}")
                    }

                    env.CODEX_FINAL_STATUS = String.valueOf(terminalHistory.status ?: 'unknown')
                    def color = env.CODEX_FINAL_STATUS == 'completed' ? 'good' : 'danger'
                    notify.codex(""":bookmark_tabs: *Codex task finished*
Task: `${env.CODEX_TASK_ID}`
Status: `${env.CODEX_FINAL_STATUS}`
Return code: `${terminalHistory.returncode ?: 'n/a'}`
Prompt: ${params.PROMPT.take(220)}
Last event: ${lastLine ?: 'n/a'}""", color)
                }
            }
        }
    }

    post {
        failure {
            script {
                notify.codex(":x: *Galahad Codex overseer failed*\nJob: `${env.JOB_NAME}`\nBuild: #${env.BUILD_NUMBER}", 'danger')
            }
        }
    }
}
