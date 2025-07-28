pipeline {
    /* 
     * ========================================================================
     * CHECKMARX ONE COMPLIANCE PIPELINE
     * ========================================================================
     * 
     * PURPOSE: This pipeline automates security scanning across multiple repositories
     *          in a GitHub organization for a specific branch/tag. It downloads the
     *          Checkmarx ONE CLI, scans repositories, and generates consolidated reports.
     * 
     * WORKFLOW:
     * 1. Prepare: Download and setup Checkmarx ONE CLI
     * 2. Find Repos: Identify repositories containing the target branch/tag
     * 3. Scan Repositories: Run security scans in parallel
     * 4. Generate Reports: Create consolidated PDF reports
     * 
     * PREREQUISITES:
     * - Jenkins credentials: 'github-pat' (GitHub Personal Access Token)
     * - Jenkins credentials: 'cx-api-key' (Checkmarx ONE API Key)
     * - Jenkins plugins: Email Extension (for notifications)
     * 
     * AUTHOR: Sean Carroll
     * LAST UPDATED: 2025
     * ========================================================================
     */
    
    /* ---------- GLOBAL CONFIG ---------- */
    agent any
    // options { ansiColor('xterm') } // (only if you later add the plugin)

    parameters {
        /* 
         * PIPELINE PARAMETERS
         * These parameters allow users to customize the pipeline behavior
         * without modifying the code. They appear in the Jenkins UI.
         */
        booleanParam(name: 'DEBUG', defaultValue: false, description: 'Enable verbose debugging output')
        string(
            name: 'BRANCH_OR_TAG',          // what the user will type in UI
            defaultValue: '25-6-x',
            description: 'Release branch or tag to scan'
        )
        string(
            name: 'GITHUB_ORG',
            defaultValue: 'CxSeanOrg2',
            description: 'GitHub organization to scan for repositories'
        )
        string(
            name: 'EMAIL_RECIPIENT',
            defaultValue: 'Sean.Carroll@checkmarx.com',
            description: 'Email address to receive consolidated reports'
        )
        string(
            name: 'CLI_VERSION',
            defaultValue: '2.0.58',
            description: 'Checkmarx ONE CLI version to download'
        )
        string(
            name: 'CRON_SCHEDULE',
            defaultValue: 'H 0 * * 0',
            description: 'Cron schedule for automated runs (e.g., H 0 * * 0 = every Sunday)'
        )
        booleanParam(name: 'FORCE_RESCAN', defaultValue: false,
                     description: 'Ignore existing status DB and rescan everything')
        string(
            name: 'CX_BASE_URL',
            defaultValue: 'https://ast.checkmarx.net',
            description: 'Checkmarx ONE base URL'
        )
        string(
            name: 'CX_IAM_URL',
            defaultValue: 'https://iam.checkmarx.net',
            description: 'Checkmarx IAM URL for OAuth token requests'
        )
        string(
            name: 'CX_OAUTH_CLIENT_ID',
            defaultValue: 'ast-app',
            description: 'OAuth client ID for Checkmarx ONE API authentication'
        )
        string(
            name: 'CX_REPORT_FORMAT',
            defaultValue: 'pdf',
            description: 'Report format (pdf, html, json, sarif, sonar, markdown)'
        )
        string(
            name: 'CX_DEFAULT_TENANT',
            defaultValue: 'workshop',
            description: 'Default Checkmarx ONE tenant (used if not found in API key)'
        )
    }

    /* Run on schedule defined by CRON_SCHEDULE parameter */
    triggers { 
        cron(params.CRON_SCHEDULE ?: 'H 0 * * 0') 
    }

    environment {
        /* 
         * ENVIRONMENT VARIABLES
         * These define paths and settings used throughout the pipeline
         */
        
        /* Folder where we cache the CLI per agent OS */
        CLI_DIR   = 'CxONE_CLI'

        /* Persistence file that survives pipeline restarts */
        STATUS_DB = '.scan_status.json'
        /* File to persist list of repos requiring scan */
        REPOS_FILE = '.repos_to_scan.json'

        /* Polling controls (5 min × 12 h max) */
        POLL_INTERVAL  = 300
        MAX_ITERATIONS = 144
    }

    /* Bind secrets from Jenkins credentials store */
    /*  - ‘github-pat’  : Secret Text  (GitHub PAT)
       - ‘cx-api-key’ : Secret Text  (Checkmarx ONE API key) */
    stages {
        stage('Prepare') {
            steps {
                withCredentials([
                    string(credentialsId: 'github-pat',  variable: 'GITHUB_TOKEN'),
                    string(credentialsId: 'cx-api-key',  variable: 'CX_API_KEY')
                ]) {
                    script {
                        /* Detect platform once */
                        IS_UNIX = isUnix()

                        /* Propagate debug flag to env so helper functions can read it */
                        env.DEBUG = params.DEBUG.toString()
                        
                        /* Log parameter values for debugging */
                        echo "Using branch/tag: ${params.BRANCH_OR_TAG}"
                        echo "Using GitHub org: ${params.GITHUB_ORG}"
                        echo "Using email recipient: ${params.EMAIL_RECIPIENT}"
                        echo "Using CLI version: ${params.CLI_VERSION}"
                        echo "Using cron schedule: ${params.CRON_SCHEDULE}"
                        echo "Force rescan: ${params.FORCE_RESCAN}"
                        echo "Using Checkmarx base URL: ${params.CX_BASE_URL}"
                        echo "Using Checkmarx IAM URL: ${params.CX_IAM_URL}"
                        echo "Using OAuth client ID: ${params.CX_OAUTH_CLIENT_ID}"
                        echo "Using report format: ${params.CX_REPORT_FORMAT}"
                        echo "Using default tenant: ${params.CX_DEFAULT_TENANT}"

                        /* -------------------------------------------------------------------
                         * CLI DOWNLOAD SECTION
                         * 
                         * PURPOSE: Download and extract the Checkmarx ONE CLI tool
                         * 
                         * LOGIC:
                         * - Check if CLI already exists to avoid re-downloading
                         * - Download from GitHub releases based on OS (Linux/Windows)
                         * - Extract the archive and clean up temporary files
                         * - Cache the CLI in workspace for reuse across builds
                         * 
                         * DOWNLOAD URLS:
                         * - Linux: https://github.com/Checkmarx/ast-cli/releases/download/{VERSION}/ast-cli_{VERSION}_linux_x64.tar.gz
                         * - Windows: https://github.com/Checkmarx/ast-cli/releases/download/{VERSION}/ast-cli_{VERSION}_windows_x64.zip
                         * ------------------------------------------------------------------- */
                        if (!fileExists("${CLI_DIR}/cx${IS_UNIX ? '' : '.exe'}")) {
                            dir(CLI_DIR) {
                                echo 'Downloading Checkmarx ONE CLI …'
                                if (IS_UNIX) {
                                    sh """
                                        curl -sL \\
                                          https://github.com/Checkmarx/ast-cli/releases/download/${params.CLI_VERSION}/ast-cli_${params.CLI_VERSION}_linux_x64.tar.gz \\
                                          -o cli.tgz
                                        tar -xzf cli.tgz
                                        rm cli.tgz
                                    """
                                } else {
                                    powershell """
                                        Invoke-WebRequest -Uri https://github.com/Checkmarx/ast-cli/releases/download/${params.CLI_VERSION}/ast-cli_${params.CLI_VERSION}_windows_x64.zip -OutFile cli.zip
                                        Expand-Archive -Path cli.zip -DestinationPath . -Force
                                        Remove-Item cli.zip
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }

        stage('Find Repos with Tag') {
            steps {
                withCredentials([
                    string(credentialsId: 'github-pat', variable: 'GITHUB_TOKEN'),
                    string(credentialsId: 'cx-api-key', variable: 'CX_API_KEY')
                ]) {
                    script {
                        def githubOrg  = params.GITHUB_ORG
                        def releaseTag = params.BRANCH_OR_TAG
                        debug("Fetching repo list for org ${githubOrg}")

                        /* --------------------------------------------------------------
                         * Identify repositories containing the target tag/branch
                         * -------------------------------------------------------------- */
                        def reposJson = httpJson(
                            "https://api.github.com/orgs/${githubOrg}/repos?per_page=100",
                            "token ${GITHUB_TOKEN}"
                        )

                        def selectedRepos = []
                        reposJson.each { repo ->
                            def repoName = repo.name
                            def apiBase = "https://api.github.com/repos/${githubOrg}/${repoName}"
                            def tagsArr = httpJson("${apiBase}/tags", "token ${GITHUB_TOKEN}")
                            def branchesArr = httpJson("${apiBase}/branches?per_page=100", "token ${GITHUB_TOKEN}")

                            def hasMatch = false
                            if (tagsArr.any { tag -> (tag instanceof Map ? tag['name'] : null) == releaseTag }) {
                                hasMatch = true
                            }
                            if (!hasMatch && branchesArr.any { br -> (br instanceof Map ? br['name'] : null) == releaseTag }) {
                                hasMatch = true
                            }

                            if (hasMatch) {
                                selectedRepos << repoName
                            }
                        }

                        writeFile file: env.REPOS_FILE, text: selectedRepos.join('\n')

                        debug("Selected repos: ${selectedRepos}")
                        if (selectedRepos) {
                            echo "Repositories that will be scanned for tag/branch '${releaseTag}':"
                            selectedRepos.each { r -> echo " - ${r}" }
                        } else {
                            echo "No repositories contain the tag/branch '${releaseTag}'."
                        }
                        echo "Found ${selectedRepos.size()} repositories with tag/branch '${releaseTag}'."
                    }
                }
            }
        }

        /* ------------------------------------------------------------------
         * Stage: Scan Repositories in parallel
         * ------------------------------------------------------------------ */
        stage('Scan Repositories') {
            when { expression { fileExists(env.REPOS_FILE) } }
            steps {
                withCredentials([
                    string(credentialsId: 'github-pat', variable: 'GITHUB_TOKEN'),
                    string(credentialsId: 'cx-api-key', variable: 'CX_API_KEY')
                ]) {
                    script {
                        def githubOrg   = params.GITHUB_ORG
                        def releaseTag  = params.BRANCH_OR_TAG
                        def reposList   = readFile(env.REPOS_FILE).split('\r?\n').findAll { it?.trim() }

                        debug("Repos to scan: ${reposList}")

                        if (!reposList) {
                            echo 'No repositories to scan.'
                            return
                        }

                        if (params.FORCE_RESCAN) {
                            echo 'FORCE_RESCAN=true – clearing status DB'
                            writeFile file: STATUS_DB, text: ''
                        }

                        def statusDb = loadStatus()
                        debug("Loaded status DB keys: ${statusDb.keySet()}")
                        def tasks    = [:]
                        def IS_UNIX  = isUnix()
                        def cliCmd   = IS_UNIX ? "${env.WORKSPACE}/${CLI_DIR}/cx" : "${env.WORKSPACE}\\${CLI_DIR}\\cx.exe"

                        reposList.each { repoName ->
                            def key = "${repoName}|${releaseTag}"
                            if (statusDb[key]?.status == 'Completed') {
                                echo "✔︎  ${key} already scanned – skipping"
                                return
                            }

                            tasks[key] = {
                                dir("${repoName}_${releaseTag}") {
                                    /* Clean previous contents if this directory was used in an earlier build */
                                    deleteDir()

                                    try {
                                        /* -------------------- Clone repo -------------------- */
                                        def cloneUrl = "https://github.com/${githubOrg}/${repoName}.git"
                                        if (IS_UNIX) {
                                        sh """
                                            git clone --depth 1 --branch ${releaseTag} ${cloneUrl} . \
                                                || git clone --depth 1 ${cloneUrl} . && git checkout ${releaseTag}
                                            """
                                        } else {
                                            bat """
                                                git clone --depth 1 --branch ${releaseTag} ${cloneUrl} .  ^
                                                || git clone --depth 1 ${cloneUrl} . & git checkout ${releaseTag}
                                            """
                                        }

                                        /* -------------------- Trigger scan ------------------- */
                                        def quote = IS_UNIX ? "'" : ""
                                        // Use env var reference on Windows to avoid Groovy secret interpolation warning
                                        def apiParam = IS_UNIX ? "--apikey ${CX_API_KEY}" : "--apikey %CX_API_KEY%"
                                        def scanCmd = "${cliCmd} scan create --project-name ${quote}Compliance/${githubOrg}/${repoName}/${releaseTag}${quote} " +
                                                      "-s . --branch ${quote}${releaseTag}${quote} --tags ${quote}release:${releaseTag}${quote} " +
                                                      apiParam

                                        def scanOut = IS_UNIX ?
                                            sh(script: scanCmd, returnStdout: true).trim() :
                                            bat(script: scanCmd, returnStdout: true).trim()

                                        // Try to extract scanId: first look for JSON, else fall back to UUID pattern
                                        String scanId
                                        String projectId = null
                                        def jsonLine = scanOut.readLines().reverse().find { it.trim().startsWith('{') }
                                        if(jsonLine) {
                                            def js = new groovy.json.JsonSlurper().parseText(jsonLine)
                                            scanId    = js.id
                                            projectId = js.projectId ?: js.projectID ?: null
                                        } else {
                                            def m = (scanOut =~ /[0-9a-fA-F-]{36}/)
                                            if (m) scanId = m[0]
                                        }
                                        if(!scanId) throw new Exception("Unable to parse scan ID from CLI output for ${key}")
                                        echo "✔︎  Scan ${scanId} finished for ${key} (the CLI blocks until completion)"

                                        statusDb[key] = [
                                            repo   : repoName,
                                            tag    : releaseTag,
                                            scanId : scanId,
                                            projectId : projectId,
                                            status : 'Completed'
                                        ]
                                    } catch (e) {
                                        statusDb[key] = [ repo: repoName, tag: releaseTag,
                                                          status: 'Failed', error: e.toString() ]
                                        echo "✖︎  Scan failed for ${key}: ${e}"
                                    }
                                }
                            }
                        }

                        if (tasks) { parallel tasks }
                        else       { echo 'Nothing to scan after filtering.' }

                        saveStatus(statusDb)
                    }
                }
            }
        }

        stage('Generate Consolidated PDF') {
            when { expression { fileExists(STATUS_DB) } }
            steps {
                withCredentials([
                    string(credentialsId: 'cx-api-key', variable: 'CX_API_KEY')
                ]) {
                    script {
                        def statusDb = loadStatus()
                        def completed = statusDb.findAll { k,v -> v.status == 'Completed' }
                        if (!completed) {
                            echo 'No finished scans – skipping report.'
                            return
                        }
                        def scanIds = completed.collect { it.value.scanId }
                        scanIds = scanIds.unique()
                        debug("ScanIds going into report: ${scanIds}")

                        /* ------------------ Get OAuth access token ------------------ */
                        def ACCESS_TOKEN = getCxAccessToken(CX_API_KEY)

                        /* ------------------ Get project IDs from scan IDs ------------------ */
                        def projectIds = []
                        scanIds.each { scanId ->
                            try {
                                def scanInfo = httpJson("${params.CX_BASE_URL}/api/scans/${scanId}", "Bearer ${ACCESS_TOKEN}", 'GET')
                                if (scanInfo.projectId) {
                                    projectIds.add(scanInfo.projectId)
                                    debug("Scan ${scanId} -> Project ${scanInfo.projectId}")
                                }
                            } catch (Exception e) {
                                debug("Could not get project ID for scan ${scanId}: ${e.message}")
                            }
                        }
                        
                        def entityType
                        def finalIds
                        if (!projectIds) {
                            echo "Could not resolve project IDs from scans. Using scan IDs instead."
                            finalIds = scanIds
                            entityType = "scan"
                        } else {
                            finalIds = projectIds.unique()
                            entityType = "project"
                        }
                        
                        def quotedIds = finalIds.collect { '"'+it+'"' }
                        debug("Final IDs going into report: ${finalIds}")

                        /* ------------------ Generate Consolidated Report via CLI ---------------------- */
                        
                        // Generate consolidated report using Checkmarx ONE API
                        echo "Generating consolidated report via API for ${finalIds.size()} ${entityType}(s)..."
                        echo "${entityType.capitalize()} IDs: ${finalIds.join(', ')}"
                        
                        // Create a clean filename for the consolidated report
                        def currentDate = new Date().format('yyyy-MM-dd')
                        def consolidatedFilename = "${params.BRANCH_OR_TAG}_${currentDate}_Consolidated"
                        def consolidatedReportFile = "${consolidatedFilename}.${params.CX_REPORT_FORMAT}"
                        
                        // Prepare the API request payload for consolidated report with email delivery
                        def payloadJson = """
                        {
                            "reportName": "improved-project-report",
                            "fileFormat": "${params.CX_REPORT_FORMAT.toLowerCase()}",
                            "reportFilename": "${consolidatedFilename}",
                            "sections": ["projects-overview", "total-vulnerabilities-overview", "vulnerabilities-insights"],
                            "entities": [
                                {
                                    "entity": "project",
                                    "ids": [${quotedIds.join(',')}],
                                    "tags": []
                                }
                            ],
                            "filters": {
                                "scanners": ["sast", "iac", "sca"],
                                "severities": ["critical", "high", "medium"],
                                "states": ["to-verify", "confirmed", "urgent"],
                                "status": ["new", "recurrent"]
                            },
                            "reportType": "ui",
                            "emails": ["${params.EMAIL_RECIPIENT}"]
                        }
                        """.trim()
                        
                        debug("API Payload for consolidated report: ${payloadJson}")
                        
                        // Retry logic for report creation
                        def maxRetries = 3
                        def retryDelay = 30 // 30 seconds between retries
                        def reportResponse = null
                        def reportId = null
                        
                        try {
                            // Check if we need to refresh the access token (it might have expired)
                            echo "[AUTH] Verifying access token is still valid..."
                            try {
                                // tokenTest variable line removed – call is used only for verification
                                httpJson(
                                    "${params.CX_BASE_URL}/api/projects",
                                    "Bearer ${ACCESS_TOKEN}",
                                    'GET',
                                    null,
                                    ['Accept': 'application/json, text/plain, */*']
                                )
                                echo "[OK] Access token is valid"
                            } catch (Exception e) {
                                echo "[WARN] Access token may have expired, refreshing..."
                                ACCESS_TOKEN = getCxAccessToken(CX_API_KEY)
                                echo "[OK] Access token refreshed"
                            }
                            
                            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                                try {
                                    echo "[RETRY] Attempt ${attempt}/${maxRetries}: Creating consolidated report..."
                                    
                                    // Make API call to create consolidated report
                                    reportResponse = httpJson(
                                        "${params.CX_BASE_URL}/api/reports/v2",
                                        "Bearer ${ACCESS_TOKEN}",
                                        'POST',
                                        payloadJson,
                                        ['Content-Type': 'application/json; version=2.0', 'Accept': 'application/json, text/plain, */*']
                                    )
                                    
                                    if (reportResponse?.reportId) {
                                        reportId = reportResponse.reportId
                                        echo "[OK] Consolidated report created with ID: ${reportId} (attempt ${attempt})"
                                        break
                                    } else {
                                        echo "[WARN] No report ID returned from API (attempt ${attempt})"
                                        if (attempt < maxRetries) {
                                            echo "[WAIT] Waiting ${retryDelay} seconds before retry..."
                                            sleep(retryDelay)
                                        }
                                    }
                                } catch (Exception e) {
                                    echo "[ERROR] Failed to create consolidated report (attempt ${attempt}): ${e.message}"
                                    if (attempt < maxRetries) {
                                        echo "[WAIT] Waiting ${retryDelay} seconds before retry..."
                                        sleep(retryDelay)
                                    } else {
                                        throw e // Re-throw on final attempt
                                    }
                                }
                            }
                            
                            if (!reportId) {
                                echo "[ERROR] Failed to create consolidated report after ${maxRetries} attempts"
                                return
                            }
                            
                            echo "[OK] Consolidated report created with ID: ${reportId}"
                            
                            // Wait for report to be generated and sent via email
                            echo "[WAIT] Waiting for consolidated report generation and email delivery..."
                            def maxWaitTime = 600 // 10 minutes (increased from 5)
                            def waitInterval = 10 // 10 seconds
                            def waited = 0
                            def reportCompleted = false
                            
                            while (waited < maxWaitTime && !reportCompleted) {
                                echo "[WAIT] Checking report status (waited ${waited}s, max ${maxWaitTime}s)..."
                                sleep(waitInterval)
                                waited += waitInterval
                                
                                // Retry logic for status check
                                def statusResponse = null
                                def statusCheckRetries = 3
                                def statusCheckRetryDelay = 5 // 5 seconds between retries
                                
                                for (int statusAttempt = 1; statusAttempt <= statusCheckRetries; statusAttempt++) {
                                    try {
                                        // Check report status
                                        echo "[CHECK] Checking status for report ID: ${reportId} (attempt ${statusAttempt}/${statusCheckRetries})"
                                        statusResponse = httpJson(
                                            "${params.CX_BASE_URL}/api/reports/${reportId}",
                                            "Bearer ${ACCESS_TOKEN}",
                                            'GET',
                                            null,
                                            ['Accept': '*/*; version=1.0']
                                        )
                                        
                                        debug("Report status: ${statusResponse}")
                                        echo "[STATUS] Current status: ${statusResponse?.status || 'Unknown'}"
                                        
                                        if (statusResponse) {
                                            break // Success, exit retry loop
                                        } else {
                                            echo "[WARN] No status response received (attempt ${statusAttempt})"
                                            if (statusAttempt < statusCheckRetries) {
                                                echo "[WAIT] Waiting ${statusCheckRetryDelay} seconds before retry..."
                                                sleep(statusCheckRetryDelay)
                                            }
                                        }
                                    } catch (Exception e) {
                                        echo "[ERROR] Status check failed (attempt ${statusAttempt}): ${e.message}"
                                        if (statusAttempt < statusCheckRetries) {
                                            echo "[WAIT] Waiting ${statusCheckRetryDelay} seconds before retry..."
                                            sleep(statusCheckRetryDelay)
                                        } else {
                                            echo "[WARN] All status check attempts failed, will try again in next polling cycle"
                                            statusResponse = null
                                        }
                                    }
                                }
                                
                                if (!statusResponse) {
                                    echo "[WARN] No status response received after ${statusCheckRetries} attempts - will retry in next cycle"
                                    continue
                                }
                                
                                if (statusResponse?.status == 'completed') {
                                    echo "[OK] Consolidated report generation completed successfully!"
                                    echo "[EMAIL] Report has been sent to: ${params.EMAIL_RECIPIENT}"
                                    echo "[INFO] Report ID: ${reportId}"
                                    echo "[INFO] Report filename: ${statusResponse.filename || consolidatedReportFile}"
                                    
                                    // Download the report using the web UI download URL
                                    try {
                                        def downloadUrl = "${params.CX_BASE_URL}/reports/download?reportId=${reportId}"
                                        def reportFilename = statusResponse.filename ? statusResponse.filename : consolidatedReportFile
                                        
                                        echo "[DOWNLOAD] Downloading consolidated report via web UI..."
                                        echo "[URL] ${downloadUrl}"
                                        
                                        // Download using the web UI endpoint (requires authentication)
                                        downloadFile(
                                            downloadUrl,
                                            reportFilename,
                                            "Bearer ${ACCESS_TOKEN}",
                                            ['Accept': 'application/pdf, application/octet-stream']
                                        )
                                        
                                        if (fileExists(reportFilename)) {
                                            def fileSize = new File(reportFilename).length()
                                            echo "[OK] Consolidated report downloaded: ${reportFilename} (size: ${fileSize} bytes)"
                                            
                                            // Archive the downloaded report
                                            echo "[ARCHIVE] Archiving consolidated report..."
                                            archiveArtifacts artifacts: reportFilename, fingerprint: true
                                            echo "[OK] Consolidated report archived successfully"
                                        } else {
                                            echo "[WARN] Consolidated report file not found after download"
                                        }
                                    } catch (Exception e) {
                                        echo "[WARN] Failed to download report: ${e.message}"
                                        echo "[INFO] Report is still available via email and in Checkmarx ONE console"
                                    }
                                    
                                    reportCompleted = true
                                    break
                                } else if (['processing','pending','started','ready'].contains(statusResponse?.status)) {
                                    echo "[WAIT] Report is still processing: ${statusResponse.status}"
                                } else if (statusResponse?.status == 'failed') {
                                    echo "[ERROR] Consolidated report generation failed: ${statusResponse.error || 'Unknown error'}"
                                    break
                                } else {
                                    echo "[WAIT] Consolidated report status: ${statusResponse?.status || 'Unknown'}"
                                    // If we get an unexpected status, log it but continue polling
                                    if (statusResponse?.status && !['completed', 'failed', 'processing', 'pending'].contains(statusResponse.status)) {
                                        echo "[WARN] Unexpected status received: ${statusResponse.status}"
                                    }
                                }
                            }
                        } // End of try block
                        catch (Exception e) { // Exception handling
                            echo "[ERROR] Failed to create consolidated report: ${e.message}"
                        }
                    
                    // Send email notification about report completion
                    if (params.EMAIL_RECIPIENT) {
                        echo "[EMAIL] Sending notification to ${params.EMAIL_RECIPIENT}..."
                        try {
                            emailext(
                                subject: "Consolidated Security Report – ${params.BRANCH_OR_TAG}",
                                body: "The consolidated security report for branch/tag '${params.BRANCH_OR_TAG}' has been generated and sent via email.\n\nReport ID: ${reportId}\nReport filename: ${consolidatedReportFile}\n\nPlease check your email for the report attachment.",
                                to: "${params.EMAIL_RECIPIENT}",
                                mimeType: 'text/plain'
                            )
                            echo "[EMAIL] Notification email sent successfully"
                        } catch (e) {
                            echo "[WARN] Failed to send email: ${e.message}"
                        }
                    }
                    
                    echo "[OK] Consolidated report generation completed successfully"
                    
                    } // End of script block
                }
            }
        }
    }

    post {
        always { echo '[FINISH] Compliance pipeline finished.' }
    }
}

/* ------------------------------------------------------------------------ */
/* ---------------------------- Helper methods ---------------------------- */
/* ------------------------------------------------------------------------ */

/* Lightweight HTTP JSON wrapper */
// Extended: extraHeaders allows us to include Accept or other headers; authHeader may be null
def httpJson(String url, String authHeader=null, String method = 'GET', String body=null, Map extraHeaders=[:]) {
    if (isUnix()) {
        def cmd = [ 'curl', '-s', '-X', method ]
        if (authHeader) {
            cmd << "-H 'Authorization: ${authHeader}'"
        }
        extraHeaders.each { k,v -> cmd << "-H '${k}: ${v}'" }
        cmd << "-H 'Content-Type: application/json'"
        cmd << url
        if (body) {
            cmd.add(cmd.size()-1, '-d')
            cmd.add(cmd.size()-1, body.replace('"', '\"'))
        }
        // Redact the auth header before printing to avoid Jenkins warnings
        def curlCmd = cmd.collect { it.startsWith('-H') && it.contains('Authorization:') ? '-H Authorization: ***' : it }.join(' ')
        debug("curl command: ${curlCmd}")
        def raw
        try {
            raw = sh(script: curlCmd, returnStdout: true).trim()
        } catch (err) {
            echo "\u001B[31mERROR executing curl: ${err}\u001B[0m"
            echo "Command was: ${curlCmd}"
            throw err
        }
        debug("Response from ${url}: ${raw.take(500)} …")
        return toSerializable( safeParse(raw) )
    } else {
        // Build PowerShell script line-by-line to avoid Groovy/DSL parsing issues
        def encodedBody = body ? body.replace("'", "''") : ''
        def psLines = []
        psLines << "\$ProgressPreference='SilentlyContinue'"
        def headerPairs = []
        
        // Handle Content-Type header - use custom one if provided, otherwise default
        def contentType = extraHeaders['Content-Type'] ?: 'application/json'
        headerPairs << "'Content-Type'='${contentType}'"
        
        if (authHeader) headerPairs << "Authorization='${authHeader}'"
        extraHeaders.each { k,v -> 
            if (k != 'Content-Type') { // Skip Content-Type as we already handled it
                headerPairs << "'${k}'='${v}'" 
            }
        }
        psLines << "\$headers = @{ ${headerPairs.join('; ')} }"
        if (body) {
            psLines << "\$body = @'\n${encodedBody}\n'@"
        }
        def restLine = "Invoke-RestMethod -Uri '${url}' -Headers \$headers -Method ${method} " + (body ? '-Body \$body' : '')
        psLines << "\$result = ${restLine}"
        psLines << "\$result | ConvertTo-Json -Compress"
        def ps = psLines.join('; ')
        
        debug("Invoke-RestMethod to ${url} (method: ${method})")
        // Remove the Authorization line before echoing
        def safePs = ps.replaceAll('Authorization=.*?;','Authorization=***;')
        debug("PowerShell script =>\n${safePs}")
        def raw
        try {
            raw = powershell(script: ps, returnStdout: true).trim()
        } catch (err) {
            echo "\u001B[31mERROR executing PowerShell Invoke-RestMethod: ${err}\u001B[0m"
            echo "Script content was:\n${ps}"
            throw err
        }
        debug("Response from ${url}: ${raw.take(500)} …")
        return toSerializable( safeParse(raw) )
    }
}

/* Download binary (e.g., PDF) without relying on curl on Windows */
def downloadFile(String url, String filePath, String authHeader = null, Map extraHeaders = [:]) {
    if (isUnix()) {
        debug("Downloading file via curl to ${filePath} from ${url}")
        try {
            def headers = []
            if (authHeader) headers << "-H 'Authorization: ${authHeader}'"
            extraHeaders.each { k,v -> headers << "-H '${k}: ${v}'" }
            def headerStr = headers.join(' ')
            sh "curl -s -L ${headerStr} ${url} -o ${filePath}"
        } catch (err) {
            echo "\u001B[31mERROR downloading file via curl: ${err}\u001B[0m"
            throw err
        }
    } else {
        debug("Downloading file via curl.exe to ${filePath} from ${url}")
        def headers = []
        if (authHeader) headers << "-H \"Authorization: ${authHeader}\""
        extraHeaders.each { k,v -> headers << "-H \"${k}: ${v}\"" }
        def headerStr = headers.join(' ')
        // First try curl.exe from the well-known system location (skip fileExists check – just try it)
        try {
            // Escape % characters in URL for Windows batch processing
            def escapedUrl = url.replace('%', '%%')
            def cmd = "%SystemRoot%\\SysNative\\curl.exe -s -L ${headerStr} \"${escapedUrl}\" -o \"${filePath}\" ^|^| %SystemRoot%\\System32\\curl.exe -s -L ${headerStr} \"${escapedUrl}\" -o \"${filePath}\""
            bat(script: cmd)
            return // success
        } catch (err) {
            echo "[WARN] curl.exe download failed (${err.message}) – falling back to Invoke-WebRequest"
        }

        // Fallback: PowerShell download
        def psLines = []
        psLines << "\$ProgressPreference='SilentlyContinue'"
        def headerPairs = []
        if (authHeader) headerPairs << "Authorization='${authHeader}'"
        extraHeaders.each { k,v -> headerPairs << "'${k}'='${v}'" }
        psLines << "\$headers = @{ ${headerPairs.join('; ')} }"

        // Use Invoke-WebRequest with increased redirection limit and direct download
        psLines << "Invoke-WebRequest -Uri '${url}' -Headers \$headers -Method Get -MaximumRedirection 10 -UseBasicParsing -OutFile '${filePath}'"
        def ps = psLines.join('; ')
        try {
            powershell(script: ps)
        } catch (err) {
            echo "\u001B[31mERROR downloading file via Invoke-WebRequest: ${err}\u001B[0m"
            echo "Script content was:\n${ps}"
            throw err
        }
    }
}

/* Poll any endpoint until extractor(js) == targetStatus */
def waitFor(url, targetStatus, extractor, headerName, headerVal) {
    for (int i = 0; i < env.MAX_ITERATIONS.toInteger(); i++) {
        def js = httpJson(url, headerVal)
        if (extractor(js).toString().equalsIgnoreCase(targetStatus)) return
        sleep env.POLL_INTERVAL.toInteger()
    }
    error "Timed-out waiting for ${url}"
}

/* Load / save status DB without plugins */
def loadStatus() {
    def m = [:]
    if (fileExists(STATUS_DB)) {
        readFile(STATUS_DB).split('\\r?\\n').each { line ->
            if (!line) return
            def parts = line.split('=', 2)
            if (parts.size() == 2) {
                def key = parts[0]
                def rest = parts[1].split('\\|', 3)
                def scan = rest.size() > 0 ? rest[0] : ''
                def status = rest.size() > 1 ? rest[1] : ''
                def proj = rest.size() > 2 ? rest[2] : ''
                m[key] = [ scanId: scan, status: status, projectId: proj ]
            }
        }
    }
    return m
}

def saveStatus(m) {
    def lines = m.collect { k, v -> "${k}=${v.scanId ?: ''}|${v.status}|${v.projectId ?: ''}" }
    writeFile file: STATUS_DB, text: lines.join('\n')
}

/* Simple conditional logger */
def debug(msg) {
    if (env.DEBUG?.toBoolean()) {
        echo "\u001B[36mDEBUG: ${msg}\u001B[0m" // cyan for visibility
    }
}

/* Convert LazyMap / GPathResult into plain LinkedHashMap so it’s Serializable */
@NonCPS
def toSerializable(obj) {
    if (obj instanceof Map) {
        def m = [:]
        obj.each { k, v -> m[k] = toSerializable(v) }
        return m
    }
    if (obj instanceof Collection) {
        return obj.collect { v -> toSerializable(v) }
    }
    return obj
}

def safeParse(raw) {
    if (!raw?.trim()) return []          // or [:] if you expect an object
    new groovy.json.JsonSlurper().parseText(raw)
}

/* ------------------------------------------------------------- */
        /* Extract tenant name from API key */
        def extractTenantFromApiKey(String apiKey) {
            // Try to extract tenant from JWT token without using decodeBase64
            try {
                // API keys are often JWT tokens that contain tenant info
                def parts = apiKey.split('\\.')
                if (parts.length >= 2) {
                    def payload = parts[1]

                    // Use shell command to decode base64 (sandbox-safe)
                    def decoded
                    if (isUnix()) {
                        decoded = sh(script: "echo '${payload}' | base64 -d", returnStdout: true).trim()
                    } else {
                        // Write to temp file and decode (certutil needs input/output files)
                        writeFile file: 'temp_payload.txt', text: payload
                        // Remove existing output file if it exists and decode
                        decoded = bat(script: "if exist temp_decoded.txt del temp_decoded.txt && certutil -decode temp_payload.txt temp_decoded.txt", returnStdout: true).trim()
                        decoded = readFile('temp_decoded.txt').trim()
                        
                        // Clean up temporary files immediately
                        bat(script: "if exist temp_payload.txt del temp_payload.txt && if exist temp_decoded.txt del temp_decoded.txt", returnStdout: true)
                    }

                    def json = new groovy.json.JsonSlurper().parseText(decoded)

                    // Look for tenant in common JWT fields
                    if (json.tenant) return json.tenant
                    if (json.realm) return json.realm
                    if (json.iss && json.iss.contains('/realms/')) {
                        def realmMatch = json.iss =~ /\/realms\/([^\/]+)/
                        if (realmMatch) return realmMatch[0][1]
                    }
                }
            } catch (Exception e) {
                debug("Could not extract tenant from API key: ${e.message}")
            }

            // Fallback: try to extract from API key format or use parameter
            if (apiKey.contains('workshop')) return 'workshop'
            if (apiKey.contains('ast-realm')) return 'ast-realm'
            if (apiKey.contains('ast-app')) return 'ast-app'

            // Default fallback: use parameter or default to workshop
            return params.CX_DEFAULT_TENANT ?: 'workshop'
        }

/* Retrieve short-lived OAuth access token using API key */
def getCxAccessToken(String apiKey) {
    def tenant = extractTenantFromApiKey(apiKey)
    debug("Extracted tenant: ${tenant}")
    def tokenUrl = "${params.CX_IAM_URL}/auth/realms/${tenant}/protocol/openid-connect/token"
    
    if (isUnix()) {
        def cmd = "curl -s -L -X POST -H 'Content-Type: application/x-www-form-urlencoded' -d 'grant_type=refresh_token&client_id=${params.CX_OAUTH_CLIENT_ID}&refresh_token=${apiKey}' ${tokenUrl}"
        def raw = sh(script: cmd, returnStdout: true).trim()
        return new groovy.json.JsonSlurper().parseText(raw).access_token
    } else {
        def curlPath = env.SystemRoot + "\\System32\\curl.exe"
        String raw
        if (fileExists(curlPath)) {
            def cmd = "${curlPath} -s -L -X POST -H \"Content-Type: application/x-www-form-urlencoded\" -d \"grant_type=refresh_token&client_id=${params.CX_OAUTH_CLIENT_ID}&refresh_token=%CX_API_KEY%\" \"${tokenUrl}\""
            raw = bat(script: cmd, returnStdout: true).trim()
        } else {
            /* Fall back to PowerShell Invoke-RestMethod */
            def ps = """
 \$ProgressPreference='SilentlyContinue';
 \$headers = New-Object "System.Collections.Generic.Dictionary[[String],[String]]"
 \$headers.Add("Content-Type", "application/x-www-form-urlencoded")
 \$body = "grant_type=refresh_token&client_id=${params.CX_OAUTH_CLIENT_ID}&refresh_token=" + \$Env:CX_API_KEY
 try {
     \$resp = Invoke-RestMethod -Uri '${tokenUrl}' -Method Post -Headers \$headers -Body \$body -MaximumRedirection 5 -ErrorAction Stop;
     \$resp | ConvertTo-Json -Compress
 } catch {
     Write-Error "Token request failed: \$(\$_.Exception.Message)"
     exit 1
 }
 """
            raw = powershell(script: ps, returnStdout: true).trim()
        }
        return new groovy.json.JsonSlurper().parseText(raw).access_token
    }
}