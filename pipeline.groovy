pipeline {
    /* 
     * ========================================================================
     * CHECKMARX ONE COMPLIANCE PIPELINE
     * ========================================================================
     * 
     * PURPOSE: This pipeline automates security scanning across multiple repositories
     *          in a GitHub organization for a specific branch/tag. It downloads the
     *          Checkmarx ONE CLI, scans repositories, and generates PDF reports using
     *          the scan results command for reliable email delivery and local file saving.
     * 
     * WORKFLOW:
     * 1. Prepare: Download and setup Checkmarx ONE CLI
     * 2. Find Repos: Identify repositories containing the target branch/tag
     * 3. Scan Repositories: Run security scans in parallel, then generate PDF reports using scan IDs
     * 
     * PREREQUISITES:
     * - Jenkins credentials: 'github-pat' (GitHub Personal Access Token)
     * - Jenkins credentials: 'cx-api-key' (Checkmarx ONE API Key)
     * - Jenkins plugins: None required (uses Checkmarx ONE API for email delivery)
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
            description: 'Email address to receive PDF reports'
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
         * These define paths and settings used throughout the pipeine
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

                        /* Propagate debug flag to env so helper fuctions can read it */
                        env.DEBUG = params.DEBUG.toString()
                        
                        /* Log parameter values for debugging */
                        echo "Using branch/tag: ${params.BRANCH_OR_TAG}"
                        echo "Using GitHub org: ${params.GITHUB_ORG}"

                        echo "Using CLI version: ${params.CLI_VERSION}"
                        echo "Using cron schedule: ${params.CRON_SCHEDULE}"
                        echo "Force rescan: ${params.FORCE_RESCAN}"
                        echo "Using Checkmarx base URL: ${params.CX_BASE_URL}"
                        echo "Using Checkmarx IAM URL: ${params.CX_IAM_URL}"
                        echo "Using OAuth client ID: ${params.CX_OAUTH_CLIENT_ID}"
                        echo "Using report format: ${params.CX_REPORT_FORMAT}"
                        echo "Using default tenant: ${params.CX_DEFAULT_TENANT}"

                        /* ------------------------------------------------------------------
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
                         * ------------------------------------------------------------------ */
                        if (!fileExists("${CLI_DIR}/cx${IS_UNIX ? '' : '.exe'}")) {
                            dir(CLI_DIR) {
                                echo 'Downloading Checkmarx ONE CLI…'
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

                        /* -------------------------------------------------------------
                         * Identify repositories containing the target tag/branch
                         * ------------------------------------------------------------- */
                        def reposJson = httpJson(
                            "https://api.github.com/orgs/${githubOrg}/repos?per_page=100",
                            "Bearer ${GITHUB_TOKEN}"
                        )

                        def selectedRepos = []
                        reposJson.each { repo ->
                            def repoName = repo.name
                            def apiBase = "https://api.github.com/repos/${githubOrg}/${repoName}"
                            def tagsArr = httpJson("${apiBase}/tags", "Bearer ${GITHUB_TOKEN}")
                            def branchesArr = httpJson("${apiBase}/branches?per_page=100", "Bearer ${GITHUB_TOKEN}")

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

        /* -----------------------------------------------------------------
         * Stage: Scan Repositories in parallel
         * ----------------------------------------------------------------- */
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
                        echo "Total repositories found: ${reposList.size()}"

                        if (!reposList) {
                            echo 'No repositories to scan.'
                            return
                        }

                        if (params.FORCE_RESCAN) {
                            echo 'FORCE_RESCAN=true – clearing status DB'
                            writeFile file: STATUS_DB, text: ''
                        } else {
                            echo 'FORCE_RESCAN=false – preserving existing status DB'
                        }

                        def statusDb = loadStatus()
                        debug("Loaded status DB keys: ${statusDb.keySet()}")
                        debug("Status DB contents: ${statusDb}")
                        def tasks    = [:]
                        def IS_UNIX  = isUnix()
                        def cliCmd   = IS_UNIX ? "${env.WORKSPACE}/${CLI_DIR}/cx" : "${env.WORKSPACE}\\${CLI_DIR}\\cx.exe"
                        
                        // Test CLI availability
                        echo "[DEBUG] Testing CLI availability: ${cliCmd}"
                        try {
                            def cliTest = IS_UNIX ?
                                sh(script: "${cliCmd} --help", returnStdout: true).trim() :
                                bat(script: "${cliCmd} --help", returnStdout: true).trim()
                            echo "[DEBUG] CLI help output (first 200 chars): ${cliTest.take(200)}"
                        } catch (Exception e) {
                            echo "[ERROR] CLI test failed: ${e.message}"
                            throw new Exception("CLI not available or not working: ${e.message}")
                        }

                        reposList.each { repoName ->
                            def key = "${repoName}|${releaseTag}"
                            debug("Checking status for key: ${key}")
                            debug("Status for ${key}: ${statusDb[key]}")
                            if (statusDb[key]?.status == 'Completed') {
                                echo "✔︎  ${key} already scanned – skipping"
                                return // This return only exits the each loop iteration, not the entire stage
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

                                        /* -------------------- Trigger scan with report generation ------------------- */
                                        def quote = IS_UNIX ? "'" : ""
                                        // Use env var reference on Windows to avoid Groovy secret interpolation warning
                                        def apiParam = IS_UNIX ? "--apikey ${CX_API_KEY}" : "--apikey %CX_API_KEY%"
                                        def currentDate = new Date().format('yyyy-MM-dd')
                                        
                                        // Try with explicit .pdf extension in output name
                                        // Step 1: Run the scan without PDF generation
                                        def scanCmd = "${cliCmd} scan create --project-name ${quote}Compliance/${githubOrg}/${repoName}/${releaseTag}${quote} " +
                                                      "-s . --branch ${quote}${releaseTag}${quote} --tags ${quote}${releaseTag}${quote} " +
                                                      "--debug " +
                                                      apiParam

                                        echo "[DEBUG] Executing scan command: ${scanCmd}"
                                        def scanOut
                                        try {
                                            scanOut = IS_UNIX ?
                                                sh(script: scanCmd, returnStdout: true).trim() :
                                                bat(script: scanCmd, returnStdout: true).trim()
                                            echo "[DEBUG] Scan command completed. Output length: ${scanOut.length()}"
                                            echo "[DEBUG] Scan command output (first 1000 chars): ${scanOut.take(1000)}"
                                        } catch (Exception e) {
                                            echo "[ERROR] Scan command failed: ${e.message}"
                                            echo "[ERROR] Full error details: ${e.toString()}"
                                            echo "[ERROR] Error class: ${e.getClass().getName()}"
                                            throw e
                                        }
                                        
                                        // Check for any error messages related to PDF generation
                                        def outputLines = scanOut.readLines()
                                        def pdfErrors = outputLines.findAll { line ->
                                            line.toLowerCase().contains('pdf') && 
                                            (line.toLowerCase().contains('error') || line.toLowerCase().contains('failed') || line.toLowerCase().contains('warning'))
                                        }
                                        if (pdfErrors) {
                                            echo "[DEBUG] Found PDF-related messages in output:"
                                            pdfErrors.each { echo "[DEBUG]   ${it}" }
                                        }

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
                                        if(!scanId) {
                                            echo "[ERROR] Unable to parse scan ID from CLI output for ${key}"
                                            echo "[ERROR] Full scan output: ${scanOut}"
                                            throw new Exception("Unable to parse scan ID from CLI output for ${key}. Output length: ${scanOut.length()}")
                                        }
                                        echo "✔︎  Scan ${scanId} finished for ${key} (the CLI blocks until completion)"
                                        
                                        // Step 2: Generate PDF report using the scan ID
                                        // Use Jenkins workspace path to ensure PDFs are saved in the main workspace
                                        def workspacePath = env.WORKSPACE
                                        echo "[DEBUG] Workspace path: ${workspacePath}"
                                        
                                        // Verify workspace directory exists and is writable
                                        if (!fileExists(workspacePath)) {
                                            echo "[ERROR] Workspace directory does not exist: ${workspacePath}"
                                            throw new Exception("Workspace directory not found")
                                        }
                                        echo "[DEBUG] Workspace directory exists and is accessible"
                                        def resultsCmd = "${cliCmd} results show --scan-id ${scanId} " +
                                                        "--report-format pdf " +
                                                        "--report-pdf-email ${params.EMAIL_RECIPIENT} " +
                                                        "--report-pdf-options ScanSummary,ScanResults " +
                                                        "--output-name ${quote}${repoName}_${releaseTag}_${currentDate}${quote} " +
                                                        "--output-path ${quote}${workspacePath}${quote} " +
                                                        "--debug " +
                                                        apiParam
                                        
                                        echo "[DEBUG] Generating PDF report with command: ${resultsCmd}"
                                        echo "[DEBUG] PDF will be saved to: ${env.WORKSPACE}/${repoName}_${releaseTag}_${currentDate}.pdf"
                                        def resultsOut
                                        try {
                                            resultsOut = IS_UNIX ?
                                                sh(script: resultsCmd, returnStdout: true).trim() :
                                                bat(script: resultsCmd, returnStdout: true).trim()
                                            echo "[DEBUG] Results command completed. Output length: ${resultsOut.length()}"
                                            echo "[DEBUG] Results command output (first 1000 chars): ${resultsOut.take(1000)}"
                                        } catch (Exception e) {
                                            echo "[ERROR] Results command failed: ${e.message}"
                                            echo "[ERROR] Full error details: ${e.toString()}"
                                            echo "[ERROR] Error class: ${e.getClass().getName()}"
                                            throw e
                                        }
                                        
                                        // Check for any error messages related to PDF generation
                                        def resultsLines = resultsOut.readLines()
                                        def resultsPdfErrors = resultsLines.findAll { line ->
                                            line.toLowerCase().contains('pdf') && 
                                            (line.toLowerCase().contains('error') || line.toLowerCase().contains('failed') || line.toLowerCase().contains('warning'))
                                        }
                                        if (resultsPdfErrors) {
                                            echo "[DEBUG] Found PDF-related messages in results output:"
                                            resultsPdfErrors.each { echo "[DEBUG]   ${it}" }
                                        }
                                        
                                        echo "📧 PDF report generation completed for scan ${scanId}"
                                        
                                        // Archive the generated PDF report if it exists
                                        def reportFile = "${repoName}_${releaseTag}_${currentDate}.pdf"
                                        
                                        // Wait a moment for file system to sync after results command
                                        sleep(5)
                                        
                                        // Check current working directory and workspace
                                        def pwd = IS_UNIX ?
                                            sh(script: "pwd", returnStdout: true).trim() :
                                            bat(script: "cd", returnStdout: true).trim()
                                        echo "[DEBUG] Current working directory: ${pwd}"
                                        echo "[DEBUG] Jenkins workspace: ${env.WORKSPACE}"
                                        
                                        // Enhanced PDF file detection and archiving
                                        def allPdfFiles = []
                                        
                                        // First, list all PDF files in workspace for debugging
                                        if (IS_UNIX) {
                                            def pdfFiles = sh(script: "find ${env.WORKSPACE} -name '*.pdf' -type f 2>/dev/null", returnStdout: true).trim()
                                            if (pdfFiles) {
                                                allPdfFiles = pdfFiles.readLines()
                                                echo "[DEBUG] All PDF files found in workspace:"
                                                allPdfFiles.each { echo "[DEBUG]   ${it}" }
                                            } else {
                                                echo "[DEBUG] No PDF files found in workspace"
                                            }
                                        } else {
                                            def pdfFiles = bat(script: "dir ${env.WORKSPACE}\\*.pdf /b 2>nul", returnStdout: true).trim()
                                            if (pdfFiles && !pdfFiles.contains("File Not Found")) {
                                                allPdfFiles = pdfFiles.readLines()
                                                echo "[DEBUG] All PDF files found in workspace:"
                                                allPdfFiles.each { echo "[DEBUG]   ${it}" }
                                            } else {
                                                echo "[DEBUG] No PDF files found in workspace"
                                            }
                                        }
                                        
                                        // Try to find and archive the specific report file
                                        def targetFile = null
                                        def possibleNames = [
                                            reportFile,
                                            "${repoName}_${releaseTag}_${currentDate}",
                                            "cx_result.pdf",
                                            "${repoName}_${releaseTag}_${currentDate}_report.pdf",
                                            "${repoName}_${releaseTag}_${currentDate}_results.pdf"
                                        ]
                                        
                                        // Check for exact matches first
                                        for (def name : possibleNames) {
                                            if (fileExists("${env.WORKSPACE}/${name}")) {
                                                targetFile = name
                                                break
                                            }
                                        }
                                        
                                        // If no exact match, look for files containing the repo name and date
                                        if (!targetFile) {
                                            for (def pdfFile : allPdfFiles) {
                                                def fileName = new File(pdfFile).getName()
                                                if (fileName.contains(repoName) && fileName.contains(currentDate)) {
                                                    targetFile = fileName
                                                    break
                                                }
                                            }
                                        }
                                        
                                        // If still no match, try any PDF file that might be our report
                                        if (!targetFile && allPdfFiles.size() > 0) {
                                            // Get the most recently modified PDF file
                                            def latestPdf = allPdfFiles.max { file ->
                                                new File(file).lastModified()
                                            }
                                            targetFile = new File(latestPdf).getName()
                                            echo "[DEBUG] Using most recent PDF file as fallback: ${targetFile}"
                                        }
                                        
                                        if (targetFile) {
                                            def fileSize = new File("${env.WORKSPACE}/${targetFile}").length()
                                            echo "[ARCHIVE] Archiving PDF report: ${targetFile} (size: ${fileSize} bytes)"
                                            archiveArtifacts artifacts: targetFile, fingerprint: true
                                            echo "[OK] PDF report archived successfully"
                                        } else {
                                            echo "[WARN] PDF report file not found: ${reportFile}"
                                            echo "[DEBUG] Checked possible names: ${possibleNames.join(', ')}"
                                            echo "[DEBUG] Total PDF files in workspace: ${allPdfFiles.size()}"
                                        }

                                        statusDb[key] = [
                                            repo   : repoName,
                                            tag    : releaseTag,
                                            scanId : scanId,
                                            projectId : projectId,
                                            status : 'Completed'
                                        ]
                                    } catch (e) {
                                        echo "[ERROR] Scan failed for ${key}: ${e}"
                                        echo "[ERROR] Error type: ${e.getClass().getName()}"
                                        echo "[ERROR] Error message: ${e.message}"
                                        echo "[ERROR] Full error: ${e.toString()}"
                                        statusDb[key] = [ repo: repoName, tag: releaseTag,
                                                          status: 'Failed', error: e.toString() ]
                                        echo "✖︎  Scan failed for ${key}: ${e}"
                                    }
                                }
                            }
                        }

                        echo "Tasks to execute: ${tasks.size()}"
                        if (tasks) { 
                            echo "Executing ${tasks.size()} parallel scan tasks..."
                            echo "[DEBUG] Task keys: ${tasks.keySet()}"
                            parallel tasks 
                        }
                        else { 
                            echo 'Nothing to scan after filtering.' 
                        }

                        debug("Saving status DB with keys: ${statusDb.keySet()}")
                        saveStatus(statusDb)
                        
                        // Summary of processing
                        def completedCount = statusDb.values().count { it.status == 'Completed' }
                        def failedCount = statusDb.values().count { it.status == 'Failed' }
                        echo "=== SCAN SUMMARY ==="
                        echo "Total repositories found: ${reposList.size()}"
                        echo "Repositories processed: ${tasks.size()}"
                        echo "Successfully completed: ${completedCount}"
                        echo "Failed: ${failedCount}"
                        echo "==================="
                    }
                }
            }
        }


    }

    post {
        always { 
            echo '[FINISH] Compliance pipeline finished.'
            
            // Archive any remaining PDF files in workspace
            script {
                def IS_UNIX = isUnix()
                if (IS_UNIX) {
                    try {
                        def pdfFiles = sh(script: "find ${env.WORKSPACE} -name '*.pdf' -type f 2>/dev/null", returnStdout: true).trim()
                        if (pdfFiles) {
                            def files = pdfFiles.readLines()
                            echo "[POST] Found ${files.size()} PDF files to archive:"
                            files.each { file ->
                                def fileName = new File(file).getName()
                                echo "[POST] Archiving: ${fileName}"
                                archiveArtifacts artifacts: fileName, fingerprint: true
                            }
                        } else {
                            echo "[POST] No PDF files found in workspace"
                        }
                    } catch (Exception e) {
                        echo "[POST] Error listing PDF files: ${e.message}"
                        echo "[POST] This is expected if no PDF files exist"
                    }
                } else {
                    try {
                        // Use a more robust Windows command that won't fail if no files exist
                        def pdfFiles = bat(script: "if exist ${env.WORKSPACE}\\*.pdf (dir ${env.WORKSPACE}\\*.pdf /b) else (echo NO_FILES)", returnStdout: true).trim()
                        if (pdfFiles && !pdfFiles.contains("NO_FILES") && !pdfFiles.contains("File Not Found")) {
                            def files = pdfFiles.readLines()
                            echo "[POST] Found ${files.size()} PDF files to archive:"
                            files.each { file ->
                                def fileName = new File(file).getName()
                                echo "[POST] Archiving: ${fileName}"
                                archiveArtifacts artifacts: fileName, fingerprint: true
                            }
                        } else {
                            echo "[POST] No PDF files found in workspace"
                        }
                    } catch (Exception e) {
                        echo "[POST] Error listing PDF files: ${e.message}"
                        echo "[POST] This is expected if no PDF files exist"
                    }
                }
            }
        }
    }
}

/* ----------------------------------------------------------------------- */
/* ---------------------------- Helper methods --------------------------- */
/* ----------------------------------------------------------------------- */

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
        // Build PowerShell script line-by-line to avoid Groovy/DSLparsing issues
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
                def rest = parts[1].split('\\|', 5) // Support up to 5 fields: scanId|status|projectId|repo|tag
                def scan = rest.size() > 0 ? rest[0] : ''
                def status = rest.size() > 1 ? rest[1] : ''
                def proj = rest.size() > 2 ? rest[2] : ''
                def repo = rest.size() > 3 ? rest[3] : ''
                def tag = rest.size() > 4 ? rest[4] : ''
                m[key] = [ 
                    scanId: scan, 
                    status: status, 
                    projectId: proj,
                    repo: repo,
                    tag: tag
                ]
            }
        }
    }
    return m
}

def saveStatus(m) {
    def lines = m.collect { k, v -> 
        "${k}=${v.scanId ?: ''}|${v.status}|${v.projectId ?: ''}|${v.repo ?: ''}|${v.tag ?: ''}" 
    }
    writeFile file: STATUS_DB, text: lines.join('\n')
}

/* Simple conditional logger */
def debug(msg) {
    if (env.DEBUG?.toBoolean()) {
        echo "\u001B[36mDEBUG: ${msg}\u001B[0m" // cyan for visibility
    }
}

/* Convert LazyMap / GPathResult into plain LinkedHashMap so it's Serializable */
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