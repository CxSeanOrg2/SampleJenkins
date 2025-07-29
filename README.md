# Checkmarx ONE Compliance Pipeline

A Jenkins pipeline that automates security scanning across multiple repositories in a GitHub organization for specific branches/tags. The pipeline downloads the Checkmarx ONE CLI (version 2.3.28), scans repositories, and generates comprehensive PDF reports using a two-step process for reliable email delivery and local file saving in the Jenkins workspace.

## Features

- **Automated Repository Discovery**: Automatically finds repositories containing the target branch/tag
- **Parallel Scanning**: Scans multiple repositories simultaneously for efficiency
- **Two-Step PDF Generation**: First runs the scan, then generates PDF reports using scan IDs for reliability
- **Email Delivery**: Sends PDF reports directly to specified email recipients
- **Local File Saving**: Saves PDF reports to Jenkins workspace with custom naming and proper archiving
- **Comprehensive Reports**: Generates detailed PDF reports with ScanSummary, ExecutiveSummary, and ScanResults sections
- **Smart Tag Management**: Uses clean tag names (e.g., "25-6-x" instead of "release:25-6-x")
- **Enhanced Persistence**: Maintains scan status across pipeline restarts
- **Force Rescan Control**: Option to ignore existing status and rescan everything
- **Enhanced Debugging**: Comprehensive logging and error handling
- **Workspace Integration**: Properly saves and archives PDF files in Jenkins workspace
- **Post-Build Archiving**: Ensures all generated PDFs are archived regardless of detection timing

## Pipeline Stages

1. **Prepare**: Downloads and sets up Checkmarx ONE CLI
2. **Find Repos with Tag**: Identifies repositories containing the target branch/tag
3. **Scan Repositories**: Runs security scans in parallel, then generates PDF reports using scan IDs

## Setup Instructions

### Step 1: Jenkins Prerequisites

Ensure your Jenkins instance has:
- **Pipeline plugin** installed and enabled
- **Git plugin** installed (for repository cloning)
- **Credentials plugin** installed (for storing secrets)
- **Workspace write access** for PDF file saving

### Step 2: Configure Jenkins Credentials

**Navigate to:** `Jenkins Dashboard` → `Manage Jenkins` → `Manage Credentials` → `System` → `Global credentials` → `Add Credentials`

#### Required Credential #1: GitHub Personal Access Token
- **Kind**: `Secret text`
- **Scope**: `Global`
- **Secret**: Your GitHub Personal Access Token
- **ID**: `github-pat` ⚠️ **Must be exactly this ID**
- **Description**: `GitHub Personal Access Token for repository access`

**How to get GitHub PAT:**
1. Go to GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token with these scopes:
   - `repo` (Full control of private repositories)
   - `read:org` (Read organization data)

#### Required Credential #2: Checkmarx ONE API Key
- **Kind**: `Secret text`
- **Scope**: `Global`
- **Secret**: Your Checkmarx ONE API Key
- **ID**: `cx-api-key` ⚠️ **Must be exactly this ID**
- **Description**: `Checkmarx ONE API Key for scanning`

**How to get Checkmarx ONE API Key:**
1. Log into your Checkmarx ONE instance
2. Go to User Settings → API Keys
3. Generate a new API key with appropriate permissions

### Step 3: Create Jenkins Pipeline Job

**Navigate to:** `Jenkins Dashboard` → `New Item`

1. **Enter an item name** (e.g., "Checkmarx Compliance Pipeline")
2. **Select** `Pipeline`
3. **Click** `OK`

### Step 4: Configure Pipeline Parameters

In your pipeline job configuration, go to the **Build Triggers** section and add these parameters:

#### Required Parameters (Must be configured):

| Parameter Name | Type | Default Value | Description | Required |
|----------------|------|---------------|-------------|----------|
| `BRANCH_OR_TAG` | String | `25-6-x` | Release branch or tag to scan | ✅ Yes |
| `GITHUB_ORG` | String | `CxSeanOrg2` | GitHub organization to scan | ✅ Yes |
| `EMAIL_RECIPIENT` | String | `your-email@company.com` | Email address to receive PDF reports | ✅ Yes |

#### Optional Parameters (Can use defaults):

| Parameter Name | Type | Default Value | Description | Required |
|----------------|------|---------------|-------------|----------|
| `DEBUG` | Boolean | `false` | Enable verbose debugging output | ❌ No |
| `FORCE_RESCAN` | Boolean | `false` | Ignore existing status DB and rescan everything | ❌ No |
| `CLI_VERSION` | String | `2.3.28` | Checkmarx ONE CLI version to download | ❌ No |
| `CRON_SCHEDULE` | String | `H 0 * * 0` | Cron schedule for automated runs (every Sunday) | ❌ No |
| `CX_BASE_URL` | String | `https://ast.checkmarx.net` | Checkmarx ONE base URL | ❌ No |
| `CX_IAM_URL` | String | `https://iam.checkmarx.net` | Checkmarx IAM URL | ❌ No |
| `CX_OAUTH_CLIENT_ID` | String | `ast-app` | OAuth client ID | ❌ No |
| `CX_REPORT_FORMAT` | String | `pdf` | Report format | ❌ No |
| `CX_REPORT_OPTIONS` | String | `ScanSummary,ExecutiveSummary,ScanResults` | PDF report sections to include | ❌ No |
| `CX_DEFAULT_TENANT` | String | `workshop` | Default tenant | ❌ No |

### Step 5: Configure Pipeline Script

In your pipeline job configuration:

1. **Pipeline Definition**: Select `Pipeline script from SCM`
2. **SCM**: Select `Git`
3. **Repository URL**: Enter your repository URL (e.g., `https://github.com/CxSeanOrg2/SampleJenkins.git`)
4. **Branch Specifier**: Enter `*/v2` (or your preferred branch)
5. **Script Path**: Enter `pipeline.groovy`

### Step 6: Configure Build Triggers (Optional)

For automated runs, in the **Build Triggers** section:
- **Poll SCM**: Check this box
- **Schedule**: Enter your cron schedule (e.g., `H 0 * * 0` for every Sunday at midnight)

### Step 7: Test the Pipeline

1. **Save** the pipeline configuration
2. **Click** `Build Now` to test
3. **Monitor** the build logs for any issues

## Configuration Examples

### Example 1: Basic Setup
```
BRANCH_OR_TAG: main
GITHUB_ORG: mycompany
EMAIL_RECIPIENT: security@mycompany.com
DEBUG: false
FORCE_RESCAN: false
```

### Example 2: Development Setup with Debug
```
BRANCH_OR_TAG: develop
GITHUB_ORG: mycompany-dev
EMAIL_RECIPIENT: dev-team@mycompany.com
DEBUG: true
FORCE_RESCAN: true
```

### Example 3: Production Setup with Scheduling
```
BRANCH_OR_TAG: release-1.0
GITHUB_ORG: mycompany-prod
EMAIL_RECIPIENT: security-team@mycompany.com
DEBUG: false
FORCE_RESCAN: false
CRON_SCHEDULE: H 2 * * 1  # Every Monday at 2 AM
```

## How It Works

### Two-Step PDF Generation Process

1. **Scan Phase**: For each repository:
   - Clones the repository with the target branch/tag
   - Runs `cx scan create` to initiate the security scan
   - Waits for scan completion and extracts the scan ID

2. **Report Generation Phase**: For each completed scan:
   - Uses `cx results show` with the scan ID to generate PDF reports
   - Sends reports via email to specified recipients
   - Saves reports locally to Jenkins workspace with custom naming
   - Archives the generated PDF files

### Status Persistence

The pipeline maintains a status database (`.scan_status.json`) that tracks:
- Repository name and tag
- Scan ID and project ID
- Scan status (Completed/Failed)

This allows the pipeline to skip already completed scans on subsequent runs, unless `FORCE_RESCAN` is enabled.

### PDF File Management

- **Naming**: Reports are named as `{repoName}_{releaseTag}_{date}.pdf`
- **Location**: Saved to Jenkins workspace root directory (`${WORKSPACE}/`)
- **Archiving**: Automatically archived as Jenkins artifacts for easy access
- **Build Page Visibility**: PDF files appear in the "Build Artifacts" section of each build
- **Download**: Files can be downloaded directly from the Jenkins build page
- **Fallback Detection**: Multiple strategies to find and archive PDF files

### Jenkins Workspace Integration

The pipeline ensures PDF files are properly integrated with Jenkins:

1. **Workspace Location**: Files are saved to `${WORKSPACE}/` (Jenkins workspace root)
2. **Artifact Archiving**: Files are automatically archived using `archiveArtifacts`
3. **Build Page Access**: PDF files appear in the "Build Artifacts" section
4. **Direct Download**: Users can download files directly from the Jenkins build page
5. **Post-Build Cleanup**: Additional archiving step ensures no files are missed

## File Structure

```
SampleJenkins/
├── pipeline.groovy          # Main Jenkins pipeline script
├── README.md               # This documentation
├── .scan_status.json       # Scan status persistence (created by pipeline)
├── .repos_to_scan.json     # Repository list (created by pipeline)
├── CxONE_CLI/              # Checkmarx ONE CLI directory (created by pipeline)
└── {repoName}_{tag}_{date}.pdf  # Generated PDF reports (created by pipeline)
```

**Note**: PDF files are automatically archived as Jenkins artifacts and appear in the "Build Artifacts" section of each build.

## Troubleshooting

### Debug Mode

Enable debug mode by setting the `DEBUG` parameter to `true` in the Jenkins UI. This provides:
- Detailed CLI command output
- File system operations logging
- Status database contents
- PDF file detection details

### Log Analysis

Key log sections to monitor:
- `[DEBUG]` messages when debug mode is enabled
- `[ARCHIVE]` messages for PDF file archiving
- `[POST]` messages for post-build archiving
- `=== SCAN SUMMARY ===` for processing statistics

## Performance Optimization

- **Parallel Processing**: Repositories are scanned in parallel
- **Status Caching**: Completed scans are skipped unless forced
- **CLI Caching**: CLI is downloaded once and reused
- **Incremental Scanning**: Only new/changed repositories are processed

## Security Considerations

- **Credential Management**: Uses Jenkins credential store for sensitive data
- **API Key Protection**: API keys are masked in logs
- **Secure Communication**: Uses HTTPS for all API calls
- **Sandbox Compatibility**: Designed to work within Jenkins script security sandbox



## Support

For issues or questions:
1. **Check Setup**: Verify all credentials are configured correctly
2. **Enable Debug**: Set `DEBUG=true` and check logs
3. **Review Troubleshooting**: Check the troubleshooting section above
4. **Check Jenkins Console**: Look for detailed error messages in build logs
5. **Verify API Access**: Test GitHub and Checkmarx API access manually

## Quick Start Checklist

- [ ] Jenkins Pipeline plugin installed
- [ ] Jenkins Credentials plugin installed
- [ ] GitHub Personal Access Token created with `repo` and `read:org` scopes
- [ ] Checkmarx ONE API Key generated
- [ ] Credential `github-pat` added to Jenkins with correct GitHub PAT
- [ ] Credential `cx-api-key` added to Jenkins with correct Checkmarx API key
- [ ] Pipeline job created with required parameters configured
- [ ] Pipeline script pointing to correct repository and branch
- [ ] Test build executed successfully 