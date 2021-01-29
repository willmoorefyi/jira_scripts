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

    results.addAll(issuesNoFeature.issues.collect { issue ->
      def result = [:]
      result.key = issue.key
      result.summary = issue.fields.summary
      result.type = issue.fields.issuetype.name
      result.created = ZonedDateTime.parse(issue.fields.created, formatter).format(DateTimeFormatter.ISO_LOCAL_DATE )
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
        .findAll { entry -> ZonedDateTime.parse(entry.created, formatter).toInstant() > dateFilter }
        .collect { entry -> 
          def historyEntry = [:]
          historyEntry.author = entry.author.displayName
          historyEntry.timestamp = ZonedDateTime.parse(entry.created, formatter).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME )
          historyEntry.changes = entry.items.collect { item ->
            def historyItem = [:]
            historyItem.field = item.field
            historyItem.from = item.fromString
            historyItem.to = item.toString
            historyItem
          }
          historyEntry
        }
      result
    })
    //results.sort { result -> result.team }
  }

  //println "history entries: ${prettyPrint(toJson(results))}"

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
          table(class: 'table table-striped table-hover') {
            tbody {
              results.each { result ->
                tr {
                  th "${result.key}"
                  th "${result.type}"
                  th "${result.created}"
                  th "${result.summary}"
                  th "${result.team}"
                }
                result.commments.each { comment ->
                  tr {
                    td ""
                    td "comment:"
                    td "${comment.timestamp}"
                    td "${comment.author}"
                    td "${comment.body}"
                  }
                }
                result.history.each { history ->
                  tr {
                    td ""
                    td "history:"
                    td "${history.timestamp}"
                    td "${history.author}"
                    td ""
                  }
                  history.changes.each { change ->
                    tr {
                      td ""
                      td ""
                      td "${change.field}"
                      td "${change.field == 'description' ? "<body>" : change.from}"
                      td "${change.field == 'description' ? "<body>" : change.to}"
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
