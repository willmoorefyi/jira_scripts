#!/usr/bin/env groovy
import java.net.http.*
import java.nio.file.*
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Grab(group='org.apache.commons', module='commons-csv', version='1.8')
import org.apache.commons.csv.*

@Grab('info.picocli:picocli-groovy:4.5.2')
@GrabConfig(systemClassLoader=true)
@Command(name = "fetch_dsm_status",
        version = "0.0.1",
        mixinStandardHelpOptions = true, // add --help and --version options
        description = "A command-line tool to fetch tickets, their group initiative milestone, and RM ticket, for a given program increment")
@picocli.groovy.PicocliScript
import static picocli.CommandLine.*
import static groovy.json.JsonOutput.*

import groovy.json.JsonSlurper
import groovy.transform.Field
import groovy.xml.*

@Option(names = ["-u", "--user"], description = 'The JIRA Username, defaults to $USERNAME. Required')
@Field String username = System.getenv().USERNAME

@Option(names = ["-p", "--password"], description = 'The JIRA password, defaults to $PASSWORD. Required')
@Field String password = System.getenv().PASSWORD

@Option(names = ["-i", "--increment"], description = 'The program increment to use when querying against "Target Delivery Date" in JIRA', required = true)
@Field String increment;

@Field final String JIRA_REST_URL = 'https://ticket.opower.com/rest/api/latest'
@Field final JsonSlurper json = new JsonSlurper()
@Field final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

@Field final Integer MAX_RESULTS = 100
// TODO look up custom field for "UGBU Scrum Team" and "Feature Link"
@Field final String scrumTeamFieldName = 'customfield_15751'
@Field final String featureLinkFieldName = 'customfield_13258'
@Field final String targetDeliveryDateFieldName = 'customfield_14670'
@Field final String groupFieldName = 'customfield_11551'
@Field final String storyPointsFieldName = 'customfield_11352'
//@Field final String sprintFieldName = 'customfield_11450'

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

def createFile() {
  final String fileName = "csv/${increment}_${DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now())}_${UUID.randomUUID().toString()}.csv"
  Files.createFile(Paths.get(fileName)).toFile()
}

def parseResults(queryResponse) {
  queryResponse.issues.collect { issue ->
    def result = [:]
    result.key = issue.key
    result.summary = issue.fields.summary
    result.type = issue.fields.issuetype.name
    ZonedDateTime created = ZonedDateTime.parse(issue.fields.created, FORMATTER)
    result.created = created.format(DateTimeFormatter.ISO_LOCAL_DATE )
    result.due = issue.fields.duedate
    result.status = issue.fields.status.name
    result.team = issue.fields[scrumTeamFieldName]?.value
    result.group = issue.fields[groupFieldName]?.value
    result.storyPoints = issue.fields[storyPointsFieldName]
    result.priority = issue.fields.priority?.name
    result.fixVersions = issue.fields.fixVersions[0]?.name
    def depKeys = issue.fields.issuelinks.findResults {link ->
      if (link.type?.name == 'Dependency') link.type.inward == 'is a dependency for' ? link.inwardIssue?.key : link.outwardIssue?.key
    }
    result.links = issue.fields.issuelinks.findResults { link ->
      if (link.type?.name == 'Feature Composition') link.type.inward == 'includes' ? link.outwardIssue?.key : link.inwardIssue?.key
    } + depKeys
    result.isDependency = depKeys != null && !depKeys.isEmpty()

    result
  }
}

def parseRMTickets(queryResponse) {
  queryResponse.issues.collect { issue ->
    def result = [:]
    result.key = issue.key
    result.summary = issue.fields.summary
    result.group = issue.fields[groupFieldName]?.value
    result.priority = issue.fields.priority?.name
    result.dependencies = issue.fields.issuelinks.findResults { link ->
      if (link.type?.name == 'Dependency') link.type.inward == 'is a dependency for' ? link.outwardIssue?.key : link.inwardIssue?.key
    }
    result.includes = issue.fields.issuelinks.findResults { link ->
      if (link.type?.name == 'Feature Composition') link.type.inward == 'includes' ? link.inwardIssue?.key : link.outwardIssue?.key
    }
    result
  }
}

def executeJql(String jql, Closure callback) {
  println "Executing Query: ${jql}"
  def request = [:]
  request.jql = jql
  request.maxResults = MAX_RESULTS
  request.fields = [ 'key', 'summary', 'issuetype', 'priority', scrumTeamFieldName, groupFieldName, storyPointsFieldName, 'fixVersions', 'created', 'issuelinks', 'duedate', 'status' ]
  request.expand = [ 'renderedFields' ]

  for (Integer startAt = 0, total = 1; startAt < total; startAt += MAX_RESULTS) {
    request.startAt = startAt
    def response = http '/search', 'POST', HttpRequest.BodyPublishers.ofString("${toJson(request)}")
    total = response.total

    println "Results found: ${total}"
    callback(response)
  }
}

println "Validating credentials"
try {
  AuthHolder.initialize(username, password)

  http '/mypermissions'

  println "Authentication success!"

  // get all linked RM tickets as "includes" or "is a dependency for"
  def roadmaps = [:]
    executeJql("""
(project = RM AND issueFunction in linkedIssuesOf(\"project = DSM AND type = Feature AND \\\"Target Delivery Date\\\" ~ ${increment}\", \"is part of\"))
 OR (project = RM AND issueFunction in linkedIssuesOf(\"project = DSM AND type = Feature AND \\\"Target Delivery Date\\\" ~ ${increment}\", \"is a dependency for\"))
 OR (project = RM AND issueFunction in linkedIssuesOf(\"project != RM AND issueFunction in linkedIssuesOf(\\\"project = DSM AND type = Feature AND cf[14670] ~ ${increment}\\\", \\\"is part of\\\")\"))
 OR (project = RM AND issueFunction in linkedIssuesOf(\"project != RM AND issueFunction in linkedIssuesOf(\\\"project = DSM AND type = Feature AND cf[14670] ~ ${increment}\\\", \\\"is a dependency for\\\")\"))
      """.trim(), { response ->
    parseRMTickets(response).each { result -> roadmaps.put(result.key, result) }
  })

  def milestones = [:]
  executeJql("project = DSM and type = \"Group Initiative Milestone\" and issueFunction in linkedIssuesOf(\"project = DSM and type = feature and \\\"Target Delivery Date\\\" ~ ${increment}\", \"is part of\")", { response -> 
    parseResults(response).each { result ->
      milestones.put(result.key, [ key: result.key, summary: result.summary, roadmap: result.links.findResult { roadmaps[it] } ])
    }
  })

  def features = [ ]
  executeJql("project = DSM AND type = Feature AND \"Target Delivery Date\" ~ ${increment}", { response ->
    parseResults(response).each { result ->
      def gim = result.links.findResult { milestones[it] }
      features << result + [ milestone: gim?.key, roadmap: gim?.roadmap?.key ? gim.roadmap?.key : result.links.findResult { linkKey ->
        result.links.findResult { roadmaps[it]?.key } ?: roadmaps.findResult { key, rm -> rm.includes.contains(linkKey) || rm.dependencies.contains(linkKey) ? key : null }
      } ]
    }
  })

  File outfile = createFile()
  println "Writing results to file ${outfile.toString()}"
  outfile.withWriter('utf-8') { writer ->
    try(CSVPrinter printer = new CSVPrinter(writer, CSVFormat.EXCEL.withHeader())) {
      printer.printRecord("Roadmaps with committed work in ${increment}")
      printer.printRecords([["key","summary","group","priority", "dependencies","includes"]] + roadmaps.collect{ key, rm -> [key, rm.summary, rm.group, rm.priority, rm.dependencies, rm.includes] })
      printer.println()
      printer.printRecord("Initiative Milestones with committed work in ${increment}")
      printer.printRecords([["key", "summary", "priority", "Roadmap", "links"]] + milestones.collect{ key, gim -> [key, gim.summary, gim.priority, gim.roadmap?.key ?: "", gim.links] })
      printer.println()
      printer.printRecord("Features committed in ${increment}")
      printer.printRecords([["key", "summary", "status", "team", "storyPoints", "fixVersion", "dependency", "GIM", "Roadmap"]] + 
        features.collect{ feature -> [ feature.key, feature.summary, feature.status, feature.team, feature.storyPoints, feature.fixVersions, feature.isDependency, feature.milestone, feature.roadmap] })
    }
    catch (IOException e) {
      println "Failed to write CSV File ${e.message}"
    }
  }
}
catch (Exception e) {
  println "${e.message}"
  org.codehaus.groovy.runtime.StackTraceUtils.sanitize(new Exception(e)).printStackTrace()
}
return
