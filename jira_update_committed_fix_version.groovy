import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.project.version.Version
import com.atlassian.jira.project.version.VersionManager
import com.atlassian.jira.web.bean.PagerFilter

import org.apache.log4j.Logger
import org.apache.log4j.Level

//final String fixVersions = "19.10,19.11,19.12,19.13,19.14,20.0,20.1,20.2,20.3,20.4"
final String fixVersions = ""
final String planningName = "2019 Q4 PP 19.14"
final String dateUsed = "2019-12-18"

final Logger log = Logger.getLogger("com.oracle.ugbu.UpdateCommittedFixVersion")
log.setLevel(Level.DEBUG)

def fixVersionList = []
if (fixVersions?.trim()) {
    log.info("Splitting fix versions ${fixVersions}")
    fixVersionList += Arrays.asList(fixVersions.split(/,/))
}
else {
    log.info("fix versions is empty")
    ProjectManager projectManager = ComponentAccessor.getProjectManager()
    VersionManager versionManager = ComponentAccessor.getVersionManager()

    Collection<Project> projects = projectManager.getProjectsFromProjectCategory(projectManager.getProjectCategoryObjectByName("DSM"))
    fixVersionList += versionManager.getAllVersionsForProjects(projects, false).inject([] as Set) { acc, version  ->
        !version.isReleased() && !version.isArchived() && acc << version.getName()
        acc
    }
    fixVersionList -= "TBD"
}

log.info("Locking in version ${fixVersionList.join(",")} for planning ${planningName} and date ${dateUsed}")

def committedFixVersionField = ComponentAccessor.getCustomFieldManager()
        .getCustomFieldObjectsByName("Committed Fix Version").first()

fixVersionList.each { fixVersion ->
    def jqlQuery = "category = DSM and type = feature and status not in (Closed, Backlog) " +
            " and fixVersion WAS \"${fixVersion}\" ON \"${dateUsed}\""
    log.info("Executing: ${jqlQuery}")

    def issueManager = ComponentAccessor.issueManager
    def user = ComponentAccessor.jiraAuthenticationContext.getLoggedInUser()
    def jqlQueryParser = ComponentAccessor.getComponent(JqlQueryParser.class)
    def searchProvider = ComponentAccessor.getComponent(SearchService.class)

    def query = jqlQueryParser.parseQuery(jqlQuery)
    def results = searchProvider.search(user, query, PagerFilter.unlimitedFilter)
    results.issues.collect { issue -> issueManager.getIssueObject(issue.id) }
            .each { issue ->
                println "Operating on issue ${issue.getKey()} ..."
                log.info("ISSUE KEY : " + issue.getKey())
                String cfv = issue.getCustomFieldValue(committedFixVersionField) as String ?: ""
                String value = cfv.split(/\n/)
                        .minus("")
                        .collect { val -> val.split(/\|/) - '' }
                        .inject([:]) { acc, val -> acc << [(val[0].trim()): val[1].trim()] }
                        .leftShift([(planningName): fixVersion])
                        .sort()
                        .inject("") { acc, val -> acc + "| ${val.key} | ${val.value} |\n" }
                        .drop(-1)

                log.info("\t\tSetting value: ${value} ")
                issue.setCustomFieldValue(committedFixVersionField, value)
                ComponentAccessor.getIssueManager().updateIssue(user, issue, EventDispatchOption.ISSUE_UPDATED, false)
            }
}
println "Done"