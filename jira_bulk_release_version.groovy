import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.version.Version
import com.atlassian.jira.project.version.VersionManager

import org.apache.log4j.Logger
import org.apache.log4j.Level

def log = Logger.getLogger("com.oracle.ugbu.BulkReleaseVersions")
log.setLevel(Level.DEBUG)

VersionManager versionManager = ComponentAccessor.getVersionManager()
Collection<Version> versions=versionManager.getVersionsByName("19.1")

log.info("Releasing version ${versions.first()?.getName()}")

versions.findAll { it.getProject().getProjectCategory()?.getName() == "DSM" }
        .findAll { !it.isReleased() && !it.isArchived() }
        .each { version ->
            log.info("""Investigating:
        version ${version.getId()} with name ${version.getName()} belongs to project ID ${version.getProject().getKey()} with release date ${version.getReleaseDate()}
          Released: ${version.isReleased()}, Archived: ${version.isArchived()}
    """)
            if(versionManager.getIssuesWithFixVersion(version).any { !(it.getStatus().getName() in ["Closed", "Done"]) }) {
                log.info("Version ${version.getName()} with project ${version.getProject().getKey()} has unreleased issues! Skipping")
            }
            else {
                log.info("Releasing version ${version.getName()} with project ${version.getProject().getKey()}")
                versionManager.releaseVersion(version, true)
            }
        }

return "Done!"

