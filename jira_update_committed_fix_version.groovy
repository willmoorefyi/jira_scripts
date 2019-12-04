import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.web.bean.PagerFilter

import org.apache.log4j.Logger
import org.apache.log4j.Level

final String fixVersion = "19.14"
final String planningName = "2019 Q4 PP 19.14"
final String dateUsed = "2019-11-20"

Logger log = Logger.getLogger("com.oracle.ugbu.UpdateCommittedFixVersion")
log.setLevel(Level.DEBUG)

log.info("Locking in version ${fixVersion} for planning ${planningName} and date ${dateUsed}")

def committedFixVersionField = ComponentAccessor.getCustomFieldManager()
        .getCustomFieldObjectsByName("Committed Fix Version").first()

def jqlQuery = "category = DSM and type = feature and status not in (Closed, Backlog) " +
        " and fixVersion WAS \"${fixVersion}\" ON \"${dateUsed}\""

def issueManager = ComponentAccessor.issueManager
def user = ComponentAccessor.jiraAuthenticationContext.getLoggedInUser()
def jqlQueryParser = ComponentAccessor.getComponent(JqlQueryParser.class)
def searchProvider = ComponentAccessor.getComponent(SearchService.class)

def query = jqlQueryParser.parseQuery(jqlQuery)
def results = searchProvider.search(user, query, PagerFilter.unlimitedFilter)
results.issues.collect { issue -> issueManager.getIssueObject(issue.id) }
        .each{issue ->
            log.info("ISSUE KEY : " + issue.getKey())
            String cfv = issue.getCustomFieldValue(committedFixVersionField) as String ?: ""
            String value = cfv.split(/\n/)
                    .minus("")
                    .collect { val -> val.split(/\|/) - '' }
                    .inject([:]) { acc, val -> acc << [ (val[0].trim()): val[1].trim() ] }
                    .leftShift([ (planningName): fixVersion ])
                    .sort()
                    .inject("") { acc, val -> acc + "| ${val.key} | ${val.value} |\n" }
                    .drop(-1)

            log.info("\t\tSetting value: ${value} ")
            issue.setCustomFieldValue(committedFixVersionField, value)
            ComponentAccessor.getIssueManager().updateIssue(user, issue, EventDispatchOption.ISSUE_UPDATED, false)
        }
