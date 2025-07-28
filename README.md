# Checkmarx ONE Compliance Pipeline

A robust Jenkins pipeline for automated compliance scanning across GitHub organizations using Checkmarx ONE. This pipeline discovers repositories with specific release branches/tags, performs parallel security scans, and generates consolidated reports with both email delivery and download capabilities.

## 🚀 Features

- **Automated Repository Discovery**: Scans GitHub organizations for repositories containing specific release branches or tags
- **Parallel Scanning**: Executes Checkmarx ONE scans concurrently across multiple repositories
- **Consolidated Report Generation**: Creates comprehensive reports combining results from multiple projects using Checkmarx ONE API v2
- **Dual Delivery**: Provides both email delivery and direct download of reports
- **Persistence**: Maintains scan status across pipeline restarts to avoid re-scanning completed repositories
- **Cross-Platform**: Works on both Unix/Linux and Windows Jenkins agents
- **Configurable**: Highly parameterized for different environments and requirements
- **Enterprise Ready**: Handles long-running scans and provides comprehensive logging

## 📋 Pipeline Stages

### 1. **Prepare**
- Downloads and caches Checkmarx ONE CLI
- Sets up environment variables
- Logs configuration parameters

### 2. **Find Repos with Tag**
- Queries GitHub API for repositories in the specified organization
- Identifies repositories containing the target branch or tag
- Creates a list of repositories to scan

### 3. **Scan Repositories** (Parallel)
- Clones each repository with the specified branch/tag
- Executes Checkmarx ONE scans using the CLI
- Stores scan results and project IDs for reporting
- Handles scan completion and error states

### 4. **Generate Consolidated PDF**
- Creates a consolidated report using Checkmarx ONE Reports Service API v2
- Combines results from multiple projects into a single comprehensive report
- Sends the report via email using Checkmarx ONE's built-in email service
- Downloads the report and archives it as a Jenkins artifact
- Provides dual delivery: email + downloadable artifact

## 🔧 Setup Instructions

### Prerequisites

1. **Jenkins Server** with appropriate plugins:
   - Pipeline plugin
   - Credentials plugin
   - Git plugin
   - Email Extension plugin (for notifications)

2. **Jenkins Agent** with:
   - Git access to your repositories
   - Internet access for CLI downloads
   - Sufficient disk space for scans and reports

### 1. Configure Jenkins Credentials

Create the following credentials in Jenkins:

#### GitHub Personal Access Token
- **Type**: Secret text
- **ID**: `github-pat`
- **Description**: GitHub PAT for repository access
- **Value**: Your GitHub Personal Access Token with `repo` scope

#### Checkmarx ONE API Key
- **Type**: Secret text  
- **ID**: `cx-api-key`
- **Description**: Checkmarx ONE API Key (JWT token) used for authentication
- **Value**: Your Checkmarx ONE API Key (JWT token) - this is used as a refresh token to obtain short-lived access tokens

### 2. Create Jenkins Pipeline Job

1. Create a new Pipeline job in Jenkins
2. Copy the contents of `pipeline.groovy` into the pipeline script
3. Configure the pipeline parameters as needed

### 3. Configure Pipeline Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `DEBUG` | `false` | Enable verbose debugging output |
| `BRANCH_OR_TAG` | `25-6-x` | Release branch or tag to scan |
| `GITHUB_ORG` | `CxSeanOrg2` | GitHub organization to scan |
| `EMAIL_RECIPIENT` | `Sean.Carroll@checkmarx.com` | Email for report notifications |
| `CLI_VERSION` | `2.0.58` | Checkmarx ONE CLI version |
| `CRON_SCHEDULE` | `H 0 * * 0` | Cron schedule for automated runs |
| `FORCE_RESCAN` | `false` | Force rescan of all repositories |
| `CX_BASE_URL` | `https://ast.checkmarx.net` | Checkmarx ONE base URL |
| `CX_IAM_URL` | `https://iam.checkmarx.net` | Checkmarx IAM URL |
| `CX_OAUTH_CLIENT_ID` | `ast-app` | OAuth client ID (used with API key for token exchange) |
| `CX_REPORT_FORMAT` | `pdf` | Report format (pdf, html, json, sarif, sonar, markdown) |
| `CX_DEFAULT_TENANT` | `workshop` | Default tenant (fallback) |

### 4. Authentication Method

The pipeline uses a hybrid authentication approach:

1. **API Key Storage**: Your Checkmarx ONE API key (JWT token) is stored as a Jenkins credential
2. **Token Exchange**: The pipeline treats this API key as a refresh token and exchanges it for short-lived access tokens
3. **OAuth Client**: Uses the configured OAuth client ID (`ast-app` by default) for the token exchange
4. **Tenant Detection**: Automatically extracts tenant information from the JWT token

**Note**: This approach provides enhanced security by using short-lived access tokens while maintaining the convenience of storing a long-lived API key.

### 5. Configure Triggers

The pipeline supports both manual and automated execution:

- **Manual**: Run with parameters through Jenkins UI
- **Automated**: Uses cron schedule (default: every Sunday at midnight)

## 📁 File Structure

```
workspace/
├── CxONE_CLI/                    # Cached CLI binaries
│   ├── cx (Unix)
│   └── cx.exe (Windows)
├── .scan_status.json             # Scan status persistence
├── .repos_to_scan.json           # Repository list
├── {repo}_25-6-x/               # Repository workspaces
│   ├── source code
│   └── scan artifacts
└── 25-6-x_2025-07-27_Consolidated.pdf  # Consolidated report
```

## 🔄 How It Works

### Repository Discovery
1. Queries GitHub API for all repositories in the organization
2. Checks each repository for the specified branch or tag
3. Creates a list of repositories to scan

### Parallel Scanning
1. Creates separate workspaces for each repository
2. Clones repositories with the target branch/tag
3. Executes Checkmarx ONE scans in parallel
4. Stores scan IDs and project IDs for reporting

### Consolidated Report Generation
1. **API-Based Generation**: Uses Checkmarx ONE Reports Service API v2
2. **Project Aggregation**: Combines results from multiple projects into a single report
3. **Email Delivery**: Sends the report via Checkmarx ONE's built-in email service
4. **Direct Download**: Downloads the report using the web UI endpoint
5. **Dual Archiving**: Archives the report as a Jenkins artifact for easy access

### Report Features
- **Comprehensive Coverage**: Includes projects overview, vulnerability insights, and total vulnerabilities
- **Multiple Scanners**: Covers SAST, SCA, and IaC results
- **Severity Filtering**: Focuses on Critical, High, and Medium severity findings
- **State Management**: Includes To-Verify, Confirmed, and Urgent findings
- **Status Tracking**: Covers both new and recurrent vulnerabilities

### Persistence
- Scan status is stored in `.scan_status.json`
- Completed scans are skipped unless `FORCE_RESCAN` is enabled
- Pipeline can be restarted without losing progress

## 🛠️ Customization

### Environment-Specific Configuration

#### Development Environment
```groovy
CX_BASE_URL = 'https://dev-ast.checkmarx.net'
CX_DEFAULT_TENANT = 'dev-tenant'
```

#### Production Environment
```groovy
CX_BASE_URL = 'https://ast.checkmarx.net'
CX_DEFAULT_TENANT = 'prod-tenant'
```

### Report Format Options

- **PDF**: Comprehensive reports with all sections (default)
- **HTML**: Web-viewable reports
- **JSON**: Machine-readable format for integration
- **SARIF**: Standard format for security tools
- **SonarQube**: Integration with SonarQube
- **Markdown**: Documentation-friendly format

### Email Integration

The pipeline uses Checkmarx ONE's built-in email service:

1. **Automatic Delivery**: Reports are sent directly from Checkmarx ONE
2. **Jenkins Notification**: Additional notification email sent via Jenkins Email Extension plugin
3. **Dual Delivery**: Both Checkmarx ONE email and Jenkins artifact available

## 🐛 Troubleshooting

### Common Issues

#### Jenkins Executor Issues
- **Problem**: "Waiting for next available executor"
- **Solution**: Check Jenkins agent status and executor configuration

#### Authentication Errors
- **Problem**: 401/403 errors from Checkmarx ONE
- **Solution**: Verify API key and tenant configuration

#### Report Generation Failures
- **Problem**: API report generation fails
- **Solution**: Check API access, project IDs, and scan completion status

#### Email Delivery Issues
- **Problem**: Reports not received via email
- **Solution**: Check Checkmarx ONE email configuration and recipient address

#### Download Failures
- **Problem**: Report download fails
- **Solution**: Check authentication and web UI endpoint access

#### Repository Access Issues
- **Problem**: Git clone failures
- **Solution**: Verify GitHub PAT permissions and repository access

### Debug Mode

Enable debug mode by setting `DEBUG = true` to see detailed logging:
- API request/response details
- CLI command execution
- File operations
- Status polling information
- Download progress

### Log Analysis

Key log indicators:
- `✅ Scan completed` - Successful scan
- `📊 Consolidated report created` - Successful report generation
- `📧 Report sent via email` - Email delivery confirmation
- `📥 Report downloaded` - Download confirmation
- `📋 Report archived` - Jenkins artifact creation
- `❌ Error` - Pipeline failures

## 📚 Reference Documentation

- [Checkmarx ONE Release Notes](https://docs.checkmarx.com/en/34965-68476-cxone-release-notes.html)
- [Checkmarx ONE API Reference](https://checkmarx.stoplight.io/docs/checkmarx-one-api-reference-guide)
- [Checkmarx ONE Reports Service API v2](https://checkmarx.stoplight.io/docs/checkmarx-one-api-reference-guide/reports-service-rest-api-export-v1.0.0)
- [Checkmarx ONE CLI Documentation](https://docs.checkmarx.com/en/34965-68625-checkmarx-one-cli-commands.html)
- [Checkmarx ONE CLI Installation](https://docs.checkmarx.com/en/34965-68625-checkmarx-one-cli-commands.html#installation)

## 🔒 Security Considerations

- API keys and tokens are stored as Jenkins credentials
- No sensitive data is logged in debug output
- Repository access is controlled via GitHub PAT
- Scan results are archived as Jenkins artifacts
- Reports are delivered via secure email and download channels

## 📈 Performance Optimization

- **Parallel scanning** reduces total execution time
- **CLI caching** avoids repeated downloads
- **Status persistence** prevents unnecessary re-scans
- **Configurable polling** allows tuning for different environments
- **Dual delivery** ensures report availability even if one method fails

## 🤝 Contributing

To contribute to this pipeline:

1. Test changes in a development environment
2. Update documentation for any new parameters
3. Ensure cross-platform compatibility
4. Add appropriate error handling
5. Update this README with any changes

## 📄 License

This pipeline is provided as-is for use with Checkmarx ONE. Please ensure compliance with your organization's security policies and Checkmarx licensing requirements. 