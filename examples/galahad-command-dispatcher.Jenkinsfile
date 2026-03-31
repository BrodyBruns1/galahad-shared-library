@Library('galahad') _

def inferIntent(String command) {
    def knownIntents = ['codex_project', 'homelab_ops', 'research', 'general']
    def text = command?.toLowerCase() ?: ''

    if (['codex', 'project', 'implement', 'build', 'create', 'setup', 'set up'].any { text.contains(it) }) {
        return 'codex_project'
    }
    if (['research', 'investigate', 'compare', 'look up', 'find out'].any { text.contains(it) }) {
        return 'research'
    }
    if (['restart', 'start', 'stop', 'container', 'docker', 'vm ', 'vm-', 'lxc', 'proxmox', 'service', 'deploy'].any { text.contains(it) }) {
        return 'homelab_ops'
    }

    def prompt = """Classify this text into exactly one of these intents: ${knownIntents.join(', ')}

Text: "${command}"

Respond with only the intent label, nothing else."""

    def llmIntent = llm.ollama(prompt, 'qwen3.5:9b')?.toLowerCase()?.trim()
    return knownIntents.contains(llmIntent) ? llmIntent : 'general'
}

pipeline {
    agent any

    parameters {
        string(name: 'COMMAND', defaultValue: 'Create a Slack slash command for Galahad and set up the Codex project flow', description: 'Natural-language request for Galahad')
        string(name: 'SOURCE', defaultValue: 'manual', description: 'Who sent the command')
    }

    stages {
        stage('Classify') {
            steps {
                script {
                    env.GALAHAD_INTENT = inferIntent(params.COMMAND)
                    currentBuild.description = env.GALAHAD_INTENT
                    notify.slack(channel: '#galahad', message: """:thinking_face: *Galahad received a command*
Source: `${params.SOURCE}`
Intent: `${env.GALAHAD_INTENT}`
Command: ${params.COMMAND.take(220)}""")
                }
            }
        }

        stage('Dispatch') {
            steps {
                script {
                    switch (env.GALAHAD_INTENT) {
                        case 'codex_project':
                            build job: 'galahad-codex-overseer',
                                wait: false,
                                parameters: [
                                    string(name: 'PROMPT', value: params.COMMAND),
                                    string(name: 'WORK_DIR', value: '/root/work'),
                                    string(name: 'GUIDANCE_MODE', value: 'notify_only'),
                                    string(name: 'POLL_SECONDS', value: '20'),
                                    string(name: 'TIMEOUT_MIN', value: '60'),
                                ]
                            notify.codex(""":rocket: *Dispatched from Galahad command dispatcher*
> ${params.COMMAND.take(220)}""")
                            break
                        case 'research':
                            notify.research(""":mag: Research request received but no dedicated research pipeline is wired yet.
> ${params.COMMAND.take(220)}""")
                            break
                        case 'homelab_ops':
                            notify.ops(""":construction: Homelab ops command received but still needs a routing job.
> ${params.COMMAND.take(220)}""")
                            break
                        default:
                            notify.galahad("I classified this as `${env.GALAHAD_INTENT}`, but only Codex-project dispatch is wired right now.")
                            break
                    }
                }
            }
        }
    }
}
