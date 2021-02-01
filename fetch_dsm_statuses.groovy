#!/usr/bin/env groovy
import java.net.http.*
import java.nio.file.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import static java.time.temporal.ChronoField.NANO_OF_SECOND;

@Grab(group='org.bitbucket.cowwoc', module='diff-match-patch', version='1.2')
import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch

@Grab('info.picocli:picocli-groovy:4.5.2')
@GrabConfig(systemClassLoader=true)
@Command(name = "create_update_version",
        version = "0.0.1",
        mixinStandardHelpOptions = true, // add --help and --version options
        description = "A command-line tool to fetch all updates to tickets for DSM over a time period")
@picocli.groovy.PicocliScript
import static picocli.CommandLine.*
import static groovy.json.JsonOutput.*

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field
import groovy.xml.*

@Field final String JIRA_REST_URL = 'https://ticket.opower.com/rest/api/latest'
@Field final JsonSlurper json = new JsonSlurper()
@Field final DateTimeFormatter jiraDateTimeFormat = DateTimeFormatter.ofPattern("d/MMM/yy")
@Field final DiffMatchPatch DMP = new DiffMatchPatch()

@Option(names = ["-u", "--user"], description = 'The JIRA Username, defaults to $USERNAME. Required')
@Field String username = System.getenv().USERNAME

@Option(names = ["-p", "--password"], description = 'The JIRA password, defaults to $PASSWORD. Required')
@Field String password = System.getenv().PASSWORD

@Option(names = ["-d", "--daysBack"], description = 'Days back to look for issue changes', required = true)
@Field Integer daysBack

class AuthHolder {
  static def instance
  final String AUTH_HEADER

  private AuthHolder(String username, String password) {
    AUTH_HEADER = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes())
  }

  static def initialize(String username, String password) {
    instance = new AuthHolder(username, password)
    return instance
  }

  static def getInstance() {
    if (instance == null) {
      throw new RuntimeException("Attempted to fetch autehntication before intialized!")
    }
    return instance;
  }
}

def http(path, method="GET", requestBody=null) {
  HttpClient client = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .build()

  def args = []
  if (requestBody) {
    args << requestBody
  }

  HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create("${JIRA_REST_URL}${path}"))
      .header("Accepts", "application/json")
      .header("Authorization", AuthHolder.getInstance().AUTH_HEADER)
      .header("Content-Type", "application/json;charset=UTF-8")
      ."${method}"(*args)
      .build()

  println "executing request ${request}"

  HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())

  if(!response.statusCode() || response.statusCode() > 299 ) {
    throw new RuntimeException("Authentication with input username / password failed! Server returned HTTP status ${response.statusCode()}")
  } else if (response.statusCode() > 499) {
    throw new RuntimeException("JIRA server error ${response.statusCode()}, please use the UI to confirm the system is up and running")
  }

  json.parseText response.body()
}

println "Validating credentials"
try {
  AuthHolder.initialize(username, password)

  http '/mypermissions'

  println "Authentication success!"

  File outfile = Files.createFile(Paths.get("output/${UUID.randomUUID().toString()}.html")).toFile()
  println "Writing results to file ${outfile.toString()}"

  final Integer maxResults = 100
  // TODO look up custom field for "UGBU Scrum Team"
  final String scrumTeamFieldName = 'customfield_15751';

  def request = [:]
  request.jql = "category = DSM AND type in (\"user story\", bug, task) AND updated > -${daysBack}d AND \"Feature Link\" is EMPTY order by \"UGBU Scrum Team\" ASC, updated ASC"
  request.maxResults = maxResults
  request.fields = [ 'key', 'summary', 'issuetype', scrumTeamFieldName, 'created', 'comment' ]
  request.expand = [ 'changelog' ]

  final Instant dateFilter = LocalDate.now().minusDays(daysBack).atStartOfDay(ZoneId.systemDefault()).toInstant()
  final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  def results = []
  for (Integer startAt = 0, total = maxResults+1; startAt + maxResults < total; startAt += maxResults) {
    request.startAt = startAt
    def issuesNoFeature = http '/search', 'POST', HttpRequest.BodyPublishers.ofString("${toJson(request)}")
    total = issuesNoFeature.total

    println "Results found: ${total}"

    results.addAll(issuesNoFeature.issues.findResults { issue ->
      def result = [:]
      result.key = issue.key
      result.summary = issue.fields.summary
      result.type = issue.fields.issuetype.name
      ZonedDateTime created = ZonedDateTime.parse(issue.fields.created, formatter)
      result.created = created.format(DateTimeFormatter.ISO_LOCAL_DATE )
      result.newlyCreated = created.toInstant() > dateFilter
      result.team = issue.fields[scrumTeamFieldName]?.value
      result.comments = issue.fields.comment?.comments
        .findAll { entry -> ZonedDateTime.parse(entry.created, formatter).toInstant() > dateFilter }
        .collect { entry ->
          def commentEntry = [:]
          commentEntry.timestamp = ZonedDateTime.parse(entry.created, formatter).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME )
          commentEntry.author = entry.author.displayName
          commentEntry.body = entry.body
          commentEntry
        }
      result.history = issue.changelog?.histories
        .findResults { entry ->
          if (ZonedDateTime.parse(entry.created, formatter).toInstant() > dateFilter) {
            def historyEntry = [:]
            historyEntry.author = entry.author.displayName
            historyEntry.timestamp = ZonedDateTime.parse(entry.created, formatter).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME )
            historyEntry.changes = entry.items.findResults { item ->
              if (! ['Rank', 'RemoteIssueLink', 'Sprint'].contains(item.field)) {
                def historyItem = [:]
                historyItem.field = item.field
                historyItem.from = item.fromString
                historyItem.to = item.toString
                List<DiffMatchPatch.Diff> diff = DMP.diffMain(historyItem.from ?: "", historyItem.to ?: "");
                DMP.diffCleanupSemantic(diff)
                historyItem.diff = DMP.diffPrettyHtml(diff)
                historyItem
              }
            }
            if (historyEntry.changes) {
              historyEntry
            }
          }
        }
        result.newlyCreated || result.comments || result.history ? result : null
    })
  }

  // println "history entries: ${prettyPrint(toJson(results))}"

  outfile.withWriter('utf-8') { writer ->
    def builder = new MarkupBuilder(writer)
    builder.html {
      head {
        meta charset: 'utf-8'
        link href: "https://cdn.jsdelivr.net/npm/bootstrap@5.0.0-beta1/dist/css/bootstrap.min.css", rel:"stylesheet", integrity:"sha384-giJF6kkoqNQ00vy+HMDP7azOuL0xtbfIcaT9wjKHr8RbDVddVHyTfAAsrekwKmP1", crossorigin:"anonymous"
        title 'DSM Status'
      }
      body(id: 'main') {
        div(class: 'container') {
          h1 'Issues without Epics'
          table(class: 'table table-hover') {
            tbody {
              results.eachWithIndex { result, idx ->
                def rowClass = result.newlyCreated ? 'table-info' : idx %2 ? '' : 'table-secondary'
                tr(class: "${rowClass}" ) {
                  th "${result.key}"
                  th "${result.type}"
                  th "${result.created}"
                  th "${result.team}"
                  th "${result.summary}"
                }
                result.comments.each { comment ->
                  tr(class: "${rowClass}" ) {
                    td ""
                    td "comment:"
                    td "${comment.timestamp}"
                    td "${comment.author}"
                    td "${comment.body}"
                  }
                }
                result.history.each { history ->
                  history.changes?.each { change ->
                    tr(class: "${rowClass}" ) {
                      td ""
                      td "history:"
                      td "${history.timestamp}"
                      td "${history.author}: ${change.field}"
                      td {
                        mkp.yieldUnescaped "${change.diff}"
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
catch (Exception e) {
  println "${e.message}"
}
return
