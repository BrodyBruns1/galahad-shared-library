/**
 * research — Galahad web research utilities
 *
 * Usage:
 *   def results = research.search('homelab kubernetes alternatives', 5)
 *   def report  = research.deep('Compare Proxmox vs ESXi for homelabs')
 */

import org.galahad.Config
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def search(String query, int numResults = 5) {
    def encoded = URLEncoder.encode(query, 'UTF-8')
    try {
        def resp = httpRequest(
            url:     "${Config.SEARXNG_URL}/search?q=${encoded}&format=json&categories=general&language=en",
            httpMode: 'GET',
            timeout: 20
        )
        def json = new JsonSlurper().parseText(resp.content)
        return json.results?.take(numResults)?.collect { r -> [
            title:   r.title,
            url:     r.url,
            snippet: r.content ?: ''
        ]} ?: []
    } catch (e) {
        echo "SearXNG search error: ${e.message}"
        return []
    }
}

def fetchPage(String url, int maxChars = 3000) {
    try {
        def resp = httpRequest(
            url:     url,
            httpMode: 'GET',
            timeout: 15,
            validResponseCodes: '200:299'
        )
        // Strip HTML tags crudely — good enough for LLM ingestion
        def text = resp.content
            .replaceAll('<script[^>]*>[\\s\\S]*?</script>', '')
            .replaceAll('<style[^>]*>[\\s\\S]*?</style>', '')
            .replaceAll('<[^>]+>', ' ')
            .replaceAll('\\s+', ' ')
            .trim()
        return text.length() > maxChars ? text.substring(0, maxChars) + '...' : text
    } catch (e) {
        echo "Fetch failed for ${url}: ${e.message}"
        return null
    }
}

def deep(String query, int numSources = 5) {
    echo "Starting deep research: ${query}"

    // 1. Search for sources
    def results = search(query, numSources)
    if (!results) {
        echo "No search results — aborting research"
        return null
    }

    // 2. Fetch and summarize each source with Ollama
    def summaries = []
    results.eachWithIndex { r, i ->
        echo "Fetching source ${i+1}/${results.size()}: ${r.url}"
        def content = fetchPage(r.url)
        if (content) {
            def summary = llm.ollama("""Summarize the key points from this web page content relevant to: "${query}"

Content:
${content}

Summary (2-3 sentences):""")
            if (summary) {
                summaries << "**${r.title}** (${r.url})\n${summary}"
            }
        }
    }

    if (!summaries) {
        echo "No summaries produced"
        return null
    }

    // 3. Synthesize final report with LM Studio (more capable for long-form)
    def sourcesText = summaries.join('\n\n---\n\n')
    def report = llm.lmstudio("""You are Galahad, a homelab AI assistant. Write a comprehensive research report answering this question:

**${query}**

Based on these sources:

${sourcesText}

Write a well-structured report with:
- Executive summary (2-3 sentences)
- Key findings (bullet points)
- Recommendations relevant to a homelab environment
- Sources referenced

Be direct and practical.""", 4096)

    return [
        query:    query,
        sources:  results.collect { it.url },
        report:   report,
        timestamp: new Date().toString()
    ]
}

def saveToLogseq(Map result) {
    if (!result?.report) return
    def date = new Date().format('yyyy_MM_dd')
    def filename = result.query.replaceAll('[^a-zA-Z0-9]', '-').toLowerCase().take(50)
    def content = """# Research: ${result.query}

date:: ${new Date().format('yyyy-MM-dd')}
tags:: research, galahad

## Summary

${result.report}

## Sources

${result.sources.collect { "- ${it}" }.join('\n')}
"""
    homelab.sshProxmox("cat > /mnt/llm-memory/logseq-vault/pages/research___${filename}.md << 'EOF'\n${content}\nEOF")
    echo "Research saved to Logseq: research___${filename}.md"
}
