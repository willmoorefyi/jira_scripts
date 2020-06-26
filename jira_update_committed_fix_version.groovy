import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.project.version.VersionManager
import com.atlassian.jira.web.bean.PagerFilter

import org.apache.log4j.Logger
import org.apache.log4j.Level

//final String fixVersions = "19.10,19.11,19.12,19.13,19.14,20.0,20.1,20.2,20.3,20.4,20.5,20.6,20.7,20.8,20.9,20.10"
final String fixVersions = ""
final String planningName = "20.4"
final String dateUsed = "2020-04-21 08:21"

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
    results.getResults().collect { issue -> issueManager.getIssueObject(issue.id) }
            .each { issue ->
                println "Operating on issue ${issue.getKey()} ..."
                log.info("ISSUE KEY : " + issue.getKey())
                String cfv = issue.getCustomFieldValue(committedFixVersionField) as String ?: ""
                String iterations = cfv.split(/\n/)
                        .minus("")
                        .findAll { val -> !val.contains('||') }
                        .collect { val -> val.split(/\|/) - '' }
                        .inject([:]) { acc, val -> acc << [(((val[0].trim()) =~ /\d{2,}\.\d{1,2}/)?.getAt(0)): val[1].trim()] }
                        .leftShift([(planningName): fixVersion])
                        .sort()
                        .inject("") { acc, val -> acc + "| PP ${val.key} | ${val.value} |\n" }
                        .drop(-1)

                String value = "|| PP Iteration || Fix Version ||\n${iterations}"
                log.info("\t\tSetting value: ${value} ")
                issue.setCustomFieldValue(committedFixVersionField, value)
                issueManager.updateIssue(user, issue, EventDispatchOption.ISSUE_UPDATED, false)
            }
}
println "Done"