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

@Grab(group='org.jsoup', module='jsoup', version='1.13.1')
import static org.jsoup.parser.Parser.unescapeEntities

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

@Option(names = ["-u", "--user"], description = 'The JIRA Username, defaults to $USERNAME. Required')
@Field String username = System.getenv().USERNAME

@Option(names = ["-p", "--password"], description = 'The JIRA password, defaults to $PASSWORD. Required')
@Field String password = System.getenv().PASSWORD

@Option(names = ["-m", "--mode"], description = 'Execution mode for this program, or granularity of data that is retrieved. Default is "stories"')
@Field Mode mode = Mode.STORIES

@Option(names = ["-d", "--daysBack"], description = 'Days back to look for issue changes', required = true)
@Field Integer daysBack

@Field final String JIRA_REST_URL = 'https://ticket.opower.com/rest/api/latest'
@Field final JsonSlurper json = new JsonSlurper()
@Field final DateTimeFormatter JIRA_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("d/MMM/yy")
@Field final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
@Field final DiffMatchPatch DMP = new DiffMatchPatch()
@Field final String ROADMAPS_JQL = "project = RM and issueFunction in linkedIssuesOf(\"project = DSM and type = 'Group Initiative Milestone'\") order by due ASC"

@Field final Integer MAX_RESULTS = 100
// TODO look up custom field for "UGBU Scrum Team" and "Feature Link"
@Field final String scrumTeamFieldName = 'customfield_15751'
@Field final String featureLinkFieldName = 'customfield_13258'

enum Mode {
  STORIES("stories"),
  FEATURES("features"),
  INITIATIVES("initiatives"),

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
        commentEntry.body = issue.renderedFields.comment.comments.find { renderendEntry -> renderendEntry.id == entry.id }.body ?: ""
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
            List<DiffMatchPatch.Diff> diff = DMP.diffMain(item.fromString ?: "", item.toString ?: "")
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
  println "Executing Query: ${jql}"
  def request = [:]
  request.jql = jql
  request.maxResults = MAX_RESULTS
  request.fields = [ 'key', 'summary', 'issuetype', scrumTeamFieldName, featureLinkFieldName, 'created', 'comment', 'issuelinks', 'duedate', 'status' ]
  request.expand = [ 'changelog', 'renderedFields' ]

  for (Integer startAt = 0, total = 1; startAt < total; startAt += MAX_RESULTS) {
    request.startAt = startAt
    def response = http '/search', 'POST', HttpRequest.BodyPublishers.ofString("${toJson(request)}")
    total = response.total

    println "Results found: ${total}"
    callback(response)
  }
}

def buildHeader(MarkupBuilder mb, Map elem, String headerBlock) {
  def statusClass = ['Done', 'Cancelled', 'Closed'].contains(elem.status) ? 'text-success'
    : ['Launching', 'Ready for Launch'].contains(elem.status) ? 'text-warning' : 'text-info'
  mb.div(class: 'row') {
    div(class: 'col-12') {
      mb."${headerBlock}" {
        a(href: "https://ticket.opower.com/browse/${elem.key}", target: '_blank', "${elem.key}")
        span(class: statusClass, elem.status)
        small(class: 'text-muted', elem.summary)
      }
    }
  }
  if (elem.due) {
    mb.div(class: 'row justify-content-end') {
      div(class: 'col-3') {
        h5 "Due: ${elem.due}"
      }
    }
  }
}

def writeTicketRow(MarkupBuilder mb, Map elem, String headerClass, String rowClass) {
  mb.tr(class: "${headerClass}" ) {
    th {
      a(href: "https://ticket.opower.com/browse/${elem.key}", target: '_blank', "${elem.key}")
    }
    th "${elem.type}"
    th "${elem.created}"
    th "${elem.team ?: ""}"
    th "${elem.summary}"
  }
  elem.comments.each { comment ->
    mb.tr(class: "${rowClass}" ) {
      td ""
      td "comment:"
      td "${comment.timestamp}"
      td "${comment.author}"
      td {
        mkp.yieldUnescaped"${unescapeEntities(comment.body, false)}"
      }
    }
  }
  elem.history.each { history ->
    history.changes?.each { change ->
      mb.tr(class: "${rowClass}" ) {
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

def buildHtml(File outfile, Closure bodyClosure) {
  println "Writing results to file ${outfile.toString()}"
  outfile.withWriter('utf-8') { writer ->
    def builder = new MarkupBuilder(writer)
    builder.html {
      head {
        meta charset: 'utf-8'
        link href: "https://cdn.jsdelivr.net/npm/bootstrap@5.0.0-beta2/dist/css/bootstrap.min.css", rel:"stylesheet", integrity:"sha384-BmbxuPwQa2lc/FVzBcNJ7UAyJxM6wuqIj61tLrc4wSX0szH/Ev+nYRRuWlolflfl", crossorigin:"anonymous"
        title 'DSM Status'
      }
      body(id: 'main') {
        div(class: 'container') {
          bodyClosure builder
        }
      }
    }
  }
}

def produceStoriesOutput() {
    // get all linked RM tickets to DSM Initiatives
  def rm = [:]
    executeJql(ROADMAPS_JQL, { response ->
    parseResults(response).each { result -> rm.put(result.key, result << [tickets: [] ]) }
  })

  // get initiatives that have updated tickets in the past X days
  def initiativeJql = "project = DSM and type = \"Group Initiative Milestone\" and issueFunction in linkedIssuesOf('category = DSM and type = feature and issueFunction in epicsOf(\"category = DSM and updated > -${daysBack}d\")') order by due ASC"
  def initiatives = [:]
  executeJql(initiativeJql, { response ->
    parseResults(response).each { result -> {
      initiatives.put(result.key, result << [tickets: [] ]) }
      def rmKey = result.links?.find { issueKey -> rm.containsKey(issueKey) }
      if (rmKey) {
        rm[rmKey].tickets.add(result)
      }
      else {
        println("warning - found initiative ${result.key} that does not belong to an RM ticket")
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

  File outfile = createFile(mode.text, daysBack)
  buildHtml outfile, { builder ->
    rm.each { rmKey, rmTicket ->
      buildHeader(builder, rmTicket, "h1")
      rmTicket.tickets.each { initiative ->
        buildHeader(builder, initiative, "h3")
        builder.table(class: 'table table-hover') {
          tbody {
            initiative.tickets.eachWithIndex { result, idx ->
              def headerClass = result.newlyCreated ? 'table-info' :
                result.type == 'Feature' ? 'table-dark' :
                idx %2 ? '' : 'table-secondary'
              def rowClass = idx %2 ? '' : 'table-secondary'

              writeTicketRow builder, result, headerClass, rowClass
            }
          }
        }
      }
    }
  }
}

def producteFeatureOutput() {
  def rm = [:]
    executeJql(ROADMAPS_JQL, { response ->
    parseResults(response).each { result -> rm.put(result.key, result << [tickets: [] ]) }
  })

  // get initiatives that have updated tickets in the past X days
  def initiativeJql = "project = DSM and type = \"Group Initiative Milestone\" and issueFunction in linkedIssuesOf('category = DSM and type = feature and issueFunction in epicsOf(\"category = DSM and updated > -${daysBack}d\")') order by due ASC"
  def initiatives = [:]
  executeJql(initiativeJql, { response ->
    parseResults(response).each { result -> {
      initiatives.put(result.key, result << [tickets: [] ]) }
    }
  })

  // get features that have updated tickets in the past X days
  def featureJql = "category = DSM and type = feature and issueFunction in epicsOf(\"category = DSM and updated > -${daysBack}d\")"
  def features = [:]
  executeJql(featureJql, { response ->
    parseResults(response).each { result ->
      result.history = result.history.findAll { history ->
        history.changes = history.changes?.findAll { change -> change.field != 'Epic Child' }
        !history.changes?.isEmpty()
      }
      if (result.newlyCreated || result.comments || result.history) {
        features.put(result.key, result << [tickets: [] ])
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

  // filter out empty initiatives
  initiatives = initiatives.findAll { initiative -> !initiative.value.tickets.empty }

  initiatives.each { initiativeKey, initiativeTicket ->
    def rmKey = initiativeTicket.links?.find { issueKey -> rm.containsKey(issueKey) }
    if (rmKey) {
      rm[rmKey].tickets.add(initiativeTicket)
    }
    else {
      prinln("warning - found initiative ${result.key} that does not belong to an RM ticket")
    }
  }

  rm = rm.findAll { rmEntry -> !rmEntry.value.tickets.empty }
  File outfile = createFile(mode.text, daysBack)
  buildHtml outfile, { builder ->
    rm.each { rmKey, rmTicket ->
      buildHeader(builder, rmTicket, "h1")
      rmTicket.tickets.each { initiative ->
        buildHeader(builder, initiative, "h3")
        builder.table(class: 'table table-hover') {
          tbody {
            initiative.tickets.eachWithIndex { result, idx ->
              def rowClass = idx %2 ? '' : 'table-secondary'
              def headerClass = result.newlyCreated ? 'table-info' : rowClass
              writeTicketRow builder, result, headerClass, rowClass
            }
          }
        }
      }
    }
  }

}

def produceInitiativesOutput() {
      // get all linked RM tickets to DSM Initiatives
  def rm = [:]
    executeJql(ROADMAPS_JQL, { response ->
    parseResults(response).each { result -> rm.put(result.key, result << [tickets: [] ]) }
  })

  // get initiatives that have updated tickets in the past X days
  def initiativeJql = "project = DSM and type = \"Group Initiative Milestone\" and (updated > -${daysBack}d OR issueFunction in linkedIssuesOf('category = DSM and type = feature and issueFunction in epicsOf(\"category = DSM and updated > -${daysBack}d\")')) order by due ASC"
  def initiatives = [:]
  executeJql(initiativeJql, { response ->
    parseResults(response).each { result -> {
      initiatives.put(result.key, result << [tickets: [] ]) }
      if (result.newlyCreated || result.comments) {
        result.history.clear
        def rmKey = result.links?.find { issueKey -> rm.containsKey(issueKey) }
        if (rmKey) {
          rm[rmKey].tickets.add(result)
        }
        else {
          prinln("warning - found initiative ${result.key} that does not belong to an RM ticket")
        }
      }
    }
  })

  rm = rm.findAll { rmEntry ->
    !rmEntry.value.tickets.empty
  }

  File outfile = createFile(mode.text, daysBack)
  buildHtml outfile, { builder ->
    rm.each { rmKey, rmTicket ->
      buildHeader(builder, rmTicket, "h1")
      builder.table(class: 'table table-hover') {
        tbody {
          rmTicket.tickets.eachWithIndex { result, idx ->
            def headerClass = result.newlyCreated ? 'table-info' :
              result.type == 'Feature' ? 'table-dark' :
              idx %2 ? '' : 'table-secondary'
            def rowClass = idx %2 ? '' : 'table-secondary'

            writeTicketRow builder, result, headerClass, rowClass
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

  switch (mode) {
    case Mode.STORIES:
      produceStoriesOutput()
      break
    case Mode.FEATURES:
      producteFeatureOutput()
      break
    case Mode.INITIATIVES:
      produceInitiativesOutput()
      break
  }
}
catch (Exception e) {
  println "${e.message}"
}
return
