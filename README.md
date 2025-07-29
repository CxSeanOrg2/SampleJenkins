# Checkmarx ONE Compliance Pipeline

A Jenkins pipeline that automates security scanning across multiple repositories in a GitHub organization for specific branches/tags. The pipeline downloads the Checkmarx ONE CLI, scans repositories, and generates PDF reports using a two-step process for reliable email delivery and local file saving.

## Features

- **Automated Repository Discovery**: Automatically finds repositories containing the target branch/tag
- **Parallel Scanning**: Scans multiple repositories simultaneously for efficiency
- **Two-Step PDF Generation**: First runs the scan, then generates PDF reports using scan IDs for reliability
- **Email Delivery**: Sends PDF reports directly to specified email recipients
- **Local File Saving**: Saves PDF reports to Jenkins workspace with custom naming
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

## Setup

### Prerequisites

- Jenkins with Pipeline plugin
- Jenkins credentials configured:
  - `github-pat`: GitHub Personal Access Token (Secret Text)
  - `cx-api-key`: Checkmarx ONE API Key (Secret Text)
- Jenkins workspace with write access for PDF file saving

### Configuration

The pipeline uses the following parameters (configurable via Jenkins UI):

- `BRANCH_OR_TAG`: Release branch or tag to scan (default: '25-6-x')
- `GITHUB_ORG`: GitHub organization to scan (default: 'CxSeanOrg2')
- `EMAIL_RECIPIENT`: Email address to receive PDF reports
- `DEBUG`: Enable verbose debugging output
- `FORCE_RESCAN`: Ignore existing status DB and rescan everything
- `CLI_VERSION`: Checkmarx ONE CLI version to download
- `CRON_SCHEDULE`: Cron schedule for automated runs

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
- **Location**: Saved to Jenkins workspace root directory
- **Archiving**: Automatically archived as Jenkins artifacts
- **Fallback Detection**: Multiple strategies to find and archive PDF files

## File Structure

```
SampleJenkins/
├── pipeline.groovy          # Main Jenkins pipeline script
├── README.md               # This documentation
├── .scan_status.json       # Scan status persistence (created by pipeline)
├── .repos_to_scan.json     # Repository list (created by pipeline)
└── Cx_ProjectReport_*.pdf  # Generated PDF reports (created by pipeline)
```

## Troubleshooting

### Debug Mode

Enable debug mode by setting the `DEBUG` parameter to `true` in the Jenkins UI. This provides:
- Detailed CLI command output
- File system operations logging
- Status database contents
- PDF file detection details

### Common Issues

#### PDF Files Not Found
- **Cause**: File system timing or naming inconsistencies
- **Solution**: The pipeline now includes enhanced file detection with multiple fallback strategies
- **Debug**: Check debug logs for file listing and detection attempts

#### Force Rescan Not Working
- **Cause**: Status database logic issues
- **Solution**: Fixed status database clearing logic and persistence
- **Debug**: Check debug logs for status DB operations

#### Only Partial Reports Sent
- **Cause**: Repository processing or email delivery issues
- **Solution**: Enhanced logging and summary reporting
- **Debug**: Check scan summary at end of pipeline

#### File Not Found Issues
- **Cause**: Workspace path or file system access problems
- **Solution**: Enhanced workspace verification and file detection
- **Debug**: Check workspace path and file existence logs

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

## Recent Fixes

### PDF Archiving Issues (Latest)
- **Problem**: PDF files were being generated but not found for archiving
- **Solution**: Enhanced file detection with multiple fallback strategies
- **Added**: Post-build archiving to ensure all PDFs are captured
- **Improved**: File system timing with longer wait periods

### Repository Processing Issues (Latest)
- **Problem**: Only 3 out of 4 repositories were being processed
- **Solution**: Enhanced logging and summary reporting
- **Added**: Detailed processing statistics
- **Fixed**: Status database logic for completed scans

### Email Delivery Issues (Previous)
- **Problem**: PDF reports not being sent via email
- **Solution**: Switched to two-step process using `cx results show`
- **Improved**: Email delivery reliability with proper CLI flags

## Support

For issues or questions:
1. Enable debug mode and check logs
2. Review the troubleshooting section
3. Check Jenkins console output for detailed error messages
4. Verify credential configuration and API access 