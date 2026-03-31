#!/usr/bin/env groovy

/**
 * pipeline.groovy — Galahad pipeline management
 *
 * Level 1: dispatch() — match natural language to an existing template pipeline and trigger it
 * Level 2: create()   — generate a brand new Jenkinsfile from a description, review, create
 */

// ─── Level 1: Template dispatch ────────────────────────────────────────────

/**
 * Dispatch a natural language command to the correct existing Jenkins pipeline.
 * Ollama classifies the command, extracts parameters, and triggers the job.
 *
 * @param command  Natural language e.g. "restart the n8n container"
 * @param hitl     Require human approval before triggering (default: false)
 * @return triggered job URL or null
 */
def dispatch(String command, boolean hitl = false) {
    def templates = [
        'docker-restart':   [job: 'galahad-docker-restart',   schema: 'CONTAINER (string)'],
        'docker-stop':      [job: 'galahad-docker-restart',   schema: 'CONTAINER (string), ACTION=stop'],
        'docker-start':     [job: 'galahad-docker-restart',   schema: 'CONTAINER (string), ACTION=start'],
        'vm-status':        [job: 'galahad-vm-status',        schema: 'VMID (string), TYPE (qemu|lxc)'],
        'vm-start':         [job: 'galahad-vm-action',        schema: 'VMID (string), ACTION=start'],
        'vm-stop':          [job: 'galahad-vm-action',        schema: 'VMID (string), ACTION=stop'],
        'vm-reboot':        [job: 'galahad-vm-action',        schema: 'VMID (string), ACTION=reboot'],
        'docker-status':    [job: 'galahad-docker-status',    schema: ''],
        'truenas-snapshot': [job: 'galahad-truenas-snapshot', schema: 'DATASET (string)'],
        'web-research':     [job: 'galahad-research',         schema: 'QUERY (string), NUM_SOURCES (int default 5)'],
    ]

    def templateNames = templates.keySet().join(', ')
    def raw = llm.call(
        "Match this command to one of these pipeline templates and extract parameters.\n" +
        "Templates: ${templateNames}\n" +
        "Command: \"${command}\"\n" +
        "Respond with only valid JSON. Example: {\"template\": \"docker-restart\", \"params\": {\"CONTAINER\": \"n8n\"}}\n" +
        "If nothing matches: {\"template\": null, \"params\": {}}",
        'fast'
    )

    def parsed = _parseJson(raw)
    if (!parsed || !parsed.template) {
        echo "pipeline.dispatch: no template matched for: ${command}"
        return null
    }

    def template = templates[parsed.template]
    if (!template) {
        echo "pipeline.dispatch: unknown template '${parsed.template}'"
        return null
    }

    echo "pipeline.dispatch: matched '${parsed.template}' → ${template.job} with params ${parsed.params}"

    if (hitl) {
        input(message: "Approve: run ${template.job} with params: ${parsed.params}?", ok: 'Run it')
    }

    return _triggerJob(template.job, parsed.params as Map)
}

// ─── Level 2: LLM pipeline generation ──────────────────────────────────────

/**
 * Generate a new Jenkins pipeline from a natural language description.
 * Shows you the generated Jenkinsfile for review before creating.
 * Always requires HITL approval.
 *
 * @param description  What the pipeline should do
 * @param jobName      Jenkins job name (auto-generated if null)
 * @param runNow       Trigger immediately after creation (default: false)
 * @return job URL or null
 */
def create(String description, String jobName = null, boolean runNow = false) {
    def jenkinsfile = llm.call(
        "Write a Jenkins declarative pipeline (Jenkinsfile) for this task:\n\"${description}\"\n\n" +
        "Rules:\n" +
        "- Start with @Library('galahad') _\n" +
        "- Use homelab.container(), homelab.vm(), homelab.lxc() for ops\n" +
        "- Use galahad.announce() in post { success } and post { failure }\n" +
        "- Add triggers { cron('...') } if scheduling is mentioned\n" +
        "- Use notify.hitl() before any destructive action\n" +
        "- Return ONLY the Jenkinsfile, no markdown, no explanation",
        'medium'
    )

    if (!jenkinsfile?.trim()) {
        echo "pipeline.create: LLM returned empty Jenkinsfile"
        return null
    }

    if (!jobName) {
        def name = llm.call(
            "Generate a short kebab-case Jenkins job name (max 5 words, no spaces, lowercase) for: \"${description}\". Return only the name, nothing else.",
            'fast'
        )
        jobName = 'galahad-' + name.trim().replaceAll(/[^a-z0-9-]/, '-').replaceAll(/-+/, '-').take(40)
    }

    echo "pipeline.create: proposed job name: ${jobName}"
    echo "pipeline.create: generated Jenkinsfile:\n${jenkinsfile}"

    def approval = input(
        message: "Review generated pipeline: ${jobName}",
        ok: 'Approve & Create',
        parameters: [
            textParam(name: 'JENKINSFILE', defaultValue: jenkinsfile,
                description: 'Edit the Jenkinsfile before approving if needed'),
            booleanParam(name: 'RUN_NOW', defaultValue: runNow,
                description: 'Trigger immediately after creation?')
        ]
    )

    def finalScript = approval?.JENKINSFILE ?: jenkinsfile
    def shouldRun   = approval?.RUN_NOW ?: false

    _createJob(jobName, finalScript)

    if (shouldRun) {
        sleep(3)
        return _triggerJob(jobName, [:])
    }

    return env.JENKINS_URL + 'job/' + jobName + '/'
}

// ─── Private helpers ────────────────────────────────────────────────────────

private def _triggerJob(String jobName, Map params) {
    def paramStr = params?.collect { k, v ->
        'name=' + k + '&value=' + java.net.URLEncoder.encode(v.toString(), 'UTF-8')
    }?.join('&') ?: ''

    withCredentials([
        string(credentialsId: 'jenkins-api-user',  variable: 'J_USER'),
        string(credentialsId: 'jenkins-api-token', variable: 'J_TOKEN')
    ]) {
        def crumbJson = sh(script: 'curl -sf -u "$J_USER:$J_TOKEN" ' + env.JENKINS_URL + 'crumbIssuer/api/json', returnStdout: true).trim()
        def crumb = _parseJson(crumbJson)
        def path = paramStr ? 'buildWithParameters?' + paramStr : 'build'
        sh 'curl -sf -X POST -u "$J_USER:$J_TOKEN" -H "' + crumb.crumbRequestField + ': ' + crumb.crumb + '" \'' + env.JENKINS_URL + 'job/' + jobName + '/' + path + '\''
    }

    echo "pipeline._triggerJob: triggered ${jobName}"
    return env.JENKINS_URL + 'job/' + jobName + '/'
}

private def _createJob(String jobName, String script) {
    def escaped = script
        .replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')

    def xml = '<?xml version=\'1.1\' encoding=\'UTF-8\'?>' +
        '<flow-definition plugin="workflow-job">' +
        '<description>Auto-generated by Galahad</description>' +
        '<keepDependencies>false</keepDependencies><properties/>' +
        '<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">' +
        '<script>' + escaped + '</script><sandbox>true</sandbox>' +
        '</definition><triggers/><disabled>false</disabled></flow-definition>'

    def encodedName = java.net.URLEncoder.encode(jobName, 'UTF-8')
    def xmlFile = "/tmp/galahad-job-${jobName}.xml"

    writeFile file: xmlFile, text: xml

    withCredentials([
        string(credentialsId: 'jenkins-api-user',  variable: 'J_USER'),
        string(credentialsId: 'jenkins-api-token', variable: 'J_TOKEN')
    ]) {
        def crumbJson = sh(script: 'curl -sf -u "$J_USER:$J_TOKEN" ' + env.JENKINS_URL + 'crumbIssuer/api/json', returnStdout: true).trim()
        def crumb = _parseJson(crumbJson)
        sh 'curl -sf -X POST -u "$J_USER:$J_TOKEN" -H "' + crumb.crumbRequestField + ': ' + crumb.crumb + '" -H "Content-Type: application/xml" --data-binary @' + xmlFile + ' \'' + env.JENKINS_URL + 'createItem?name=' + encodedName + '\''
    }

    echo "pipeline._createJob: created job '${jobName}'"
}

private def _parseJson(String text) {
    try {
        def clean = text.replaceAll(/(?s)^[^{\[]*/, '').replaceAll(/(?s)[}\])[^}\]]*$/, { it[0] })
        return new groovy.json.JsonSlurperClassic().parseText(clean)
    } catch (e) {
        echo "pipeline._parseJson failed on: ${text?.take(200)}"
        return null
    }
}
