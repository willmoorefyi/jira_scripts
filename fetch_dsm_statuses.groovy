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
@Command(name = "fetch_dsm_status",
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
@Field final DateTimeFormatter JIRA_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("d/MMM/yy")
@Field final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
@Field final DiffMatchPatch DMP = new DiffMatchPatch()

@Field final Integer MAX_RESULTS = 100
// TODO look up custom field for "UGBU Scrum Team" and "Feature Link"
@Field final String scrumTeamFieldName = 'customfield_15751'
@Field final String featureLinkFieldName = 'customfield_13258'

@Option(names = ["-u", "--user"], description = 'The JIRA Username, defaults to $USERNAME. Required')
@Field String username = System.getenv().USERNAME

@Option(names = ["-p", "--password"], description = 'The JIRA password, defaults to $PASSWORD. Required')
@Field String password = System.getenv().PASSWORD

@Option(names = ["-m", "--mode"], description = 'Execution mode for this program, or granularity of data that is retrieved. Default is "stories"')
@Field Mode mode = Mode.STORIES

@Option(names = ["-d", "--daysBack"], description = 'Days back to look for issue changes', required = true)
@Field Integer daysBack

enum Mode {
  STORIES("stories"),
  FEATURES("features"),
  INITIATIVES("initiatives"),
  ROADMAPS("roadmaps")

  final String text
  Mode(String text) {
    this.text = text
  }
}

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

def createFile(String classifier, Integer daysBack) {
  final DateTimeFormatter formatter = DateTimeFormatter.BASIC_ISO_DATE
  final String fileName = """
      output/${classifier}_${formatter.format(LocalDate.now())}_${formatter.format(LocalDate.now().minusDays(daysBack))}_${UUID.randomUUID().toString()}.html
    """.trim()
  Files.createFile(Paths.get(fileName)).toFile()
}

def parseResults(queryResponse) {
  final Instant dateFilter = LocalDate.now().minusDays(daysBack).atStartOfDay(ZoneId.systemDefault()).toInstant()
  queryResponse.issues.collect { issue ->
    def result = [:]
    result.key = issue.key
    result.summary = issue.fields.summary
    result.type = issue.fields.issuetype.name
    ZonedDateTime created = ZonedDateTime.parse(issue.fields.created, FORMATTER)
    result.created = created.format(DateTimeFormatter.ISO_LOCAL_DATE )
    result.newlyCreated = created.toInstant() > dateFilter
    result.due = issue.fields.duedate
    result.status = issue.fields.status.name
    result.team = issue.fields[scrumTeamFieldName]?.value
    result.feature = issue.fields[featureLinkFieldName]
    result.comments = issue.fields.comment?.comments
      .findAll { entry -> ZonedDateTime.parse(entry.created, FORMATTER).toInstant() > dateFilter }
      .collect { entry ->
        def commentEntry = [:]
        commentEntry.timestamp = ZonedDateTime.parse(entry.created, FORMATTER).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME )
        commentEntry.author = entry.author?.displayName
        commentEntry.body = entry.body
        commentEntry
      }
    result.history = issue.changelog?.histories.findResults { entry ->
      if (ZonedDateTime.parse(entry.created, FORMATTER).toInstant() > dateFilter) {
        def historyEntry = [:]
        historyEntry.author = entry.author?.displayName
        historyEntry.timestamp = ZonedDateTime.parse(entry.created, FORMATTER).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME )
        historyEntry.changes = entry.items.findResults { item ->
          if (! ['Rank', 'RemoteIssueLink', 'Sprint', 'Committed Fix Version'].contains(item.field)) {
            def historyItem = [:]
            historyItem.field = item.field
            List<DiffMatchPatch.Diff> diff = DMP.diffMain(item.fromString ?: "", item.toString ?: "");
            DMP.diffCleanupSemantic(diff)
            historyItem.diff = DMP.diffPrettyHtml(diff)
            historyItem
          }
        }
        historyEntry.changes ? historyEntry : null
      }
    }
    result.links = issue.fields.issuelinks.findResults { link -> link.inwardIssue?.key ?: link.outwardIssue?.key }
    result
  }
}

def executeJql(String jql, Closure callback) {

  def request = [:]
  request.jql = jql
  request.maxResults = MAX_RESULTS
  request.fields = [ 'key', 'summary', 'issuetype', scrumTeamFieldName, featureLinkFieldName, 'created', 'comment', 'issuelinks', 'duedate', 'status' ]
  request.expand = [ 'changelog' ]

  for (Integer startAt = 0, total = 1; startAt < total; startAt += MAX_RESULTS) {
    request.startAt = startAt
    def response = http '/search', 'POST', HttpRequest.BodyPublishers.ofString("${toJson(request)}")
    total = response.total

    println "Results found: ${total}"
    callback(response)
  }
}

def buildHtml(File outfile, def topLevel) {
  println "Writing results to file ${outfile.toString()}"
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
          topLevel.each { rmKey, rmTicket ->
            def rmStatusClass = ['Done', 'Cancelled', 'Closed'].contains(rmTicket.status) ? 'text-success'
              : ['Launching', 'Ready for Launch'].contains(rmTicket.status) ? 'text-warning' : 'text-info'
            div(class: 'row') {
              div(class: 'col-12') {
                h1 {
                  a(href: "https://ticket.opower.com/browse/${rmKey}", target: '_blank', "${rmKey}")
                  span(class: rmStatusClass, rmTicket.status)
                  small(class: 'text-muted', rmTicket.summary)
                }
              }
            }
            if (rmTicket.due) {
              div(class: 'row justify-content-end') {
                div(class: 'col-3') {
                  h5 "Due: ${rmTicket.due}"
                }
              }
            }
            rmTicket.tickets.each { initiative ->
              if (initiative.summary) {
                def initiativeStatusClass = ['Done', 'Cancelled', 'Closed'].contains(initiative.status) ? 'text-success'
                  : ['Launching', 'Ready for Launch'].contains(initiative.status) ? 'text-warning' : 'text-info'
                div(class: 'row') {
                  div(class: 'col-12') {
                    h3 {
                      a(href: "https://ticket.opower.com/browse/${initiative.key}", target: '_blank', "${initiative.key}")
                      span(class: initiativeStatusClass, initiative.status)
                      small(class: 'text-muted', initiative.summary)
                    }
                  }
                }
                div(class: 'row justify-content-end') {
                  div(class: 'col-3') {
                    h5 "Due: ${initiative.due}"
                  }
                }
              }
              table(class: 'table table-hover') {
                tbody {
                  initiative.tickets.eachWithIndex { result, idx ->
                    def headerClass = result.newlyCreated ? 'table-info' :
                      result.type == 'Feature' ? 'table-dark' :
                      idx %2 ? '' : 'table-secondary'
                    def rowClass = idx %2 ? '' : 'table-secondary'
                    tr(class: "${headerClass}" ) {
                      th {
                        a(href: "https://ticket.opower.com/browse/${result.key}", target: '_blank', "${result.key}")
                      }
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
  }
}

println "Validating credentials"
try {
  AuthHolder.initialize(username, password)

  http '/mypermissions'

  println "Authentication success!"

  // get all linked RM tickets to DSM Initiatives
  def rmJql = "project = RM and issueFunction in linkedIssuesOf(\"project = DSM and type = 'Group Initiative'\") order by due ASC"
  def rm = [:]
    executeJql(rmJql, { response ->
    parseResults(response).each { result -> rm.put(result.key, result << [tickets: [] ]) }
  })

  def roadmapsJql = "project = DSM and type = \"Group Initiative\" and issueFunction in linkedIssuesOf('category = DSM and type = feature and issueFunction in epicsOf(\"category = DSM and updated > -${daysBack}d\")') order by due ASC"

  // get initiatives that have updated tickets in the past X days
  def initiatives = [:]
  executeJql(roadmapsJql, { response ->
    parseResults(response).each { result -> {
      initiatives.put(result.key, result << [tickets: [] ]) }
      def rmKey = result.links?.find { issueKey -> rm.containsKey(issueKey) }
      if (rmKey) {
        rm[rmKey].tickets.add(result)
      }
      else {
        prinln("warning - found initiative ${result.key} that does not belong to an RM ticket")
      }
    }
  })

  // get features that have updated tickets in the past X days
  def featureJql = "category = DSM and type = feature and issueFunction in epicsOf(\"category = DSM and updated > -${daysBack}d\")"
  def features = [:]
  executeJql(featureJql, { response ->
    parseResults(response).each { result -> features.put(result.key, result << [tickets: [] ]) }
  })

  def issuesWithFeaturesJql = "category = DSM AND type in (\"user story\", bug, task) AND updated > -${daysBack}d AND \"Feature Link\" is NOT EMPTY order by \"UGBU Scrum Team\" ASC, updated ASC"
  executeJql(issuesWithFeaturesJql, { response ->
    parseResults(response).findAll { result -> result.newlyCreated || result.comments || result.history }.each { issue ->
      if (features.containsKey(issue.feature)) {
        features[issue.feature].tickets.add(issue)
      }
      else {
        println "found issue ${issue.key} with no associated feature ${issue.feature}!"
      }
    }
  })

  // map features to roadmaps
  rm.put('NI', [  key: 'NI', summary: 'Features without Initiatives', tickets: [  ] ])
  def ni = [ key: 'NI', tickets: [ ] ]
  features.each { key, feature ->
    def initiativeKey = feature.links?.find { issueKey -> initiatives.containsKey(issueKey) }
    if (initiativeKey) {
      initiatives[initiativeKey].tickets.add(feature)
      initiatives[initiativeKey].tickets.addAll(feature.tickets)
    }
    else {
      ni.tickets.add(feature)
      ni.tickets.addAll(feature.tickets)
    }
  }

  rm['NI'].tickets << ni

  String issuesNoFeaturesJql = "category = DSM AND type in (\"user story\", bug, task) AND updated > -${daysBack}d AND \"Feature Link\" is EMPTY order by \"UGBU Scrum Team\" ASC, updated ASC"
  rm.put('NF', [ key: 'NF', summary:'Issues without Features', tickets: [  ] ])
  def nf = [ key: 'NF', tickets: [ ] ]
  executeJql(issuesNoFeaturesJql, { response ->
    nf.tickets.addAll(parseResults(response).findAll { result ->
      result.newlyCreated || result.comments || result.history
    })
  })

  rm['NF'].tickets << nf

  rm = rm.findAll { rmEntry ->
    !rmEntry.value.tickets.empty
  }

  // println "Results:\n${prettyPrint(toJson(rm))}"
  File outfile = createFile(mode.text, daysBack)
  buildHtml outfile, rm
}
catch (Exception e) {
  println "${e.message}"
}
return
