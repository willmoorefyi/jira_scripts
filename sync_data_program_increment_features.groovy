#!/usr/bin/env groovy
import java.net.http.*
import java.nio.file.*
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Grab('info.picocli:picocli-groovy:4.5.2')
@GrabConfig(systemClassLoader=true)
@Command(name = "sync_data_program_increment_features",
        version = "0.0.1",
        mixinStandardHelpOptions = true, // add --help and --version options
        description = "A command-line tool to fetch tickets, their group initiative milestone, and RM ticket, for a given program increment")
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

@Option(names = ["-i", "--increment"], description = 'The program increment to use when querying against "Target Delivery Date" in JIRA', required = true)
@Field String increment;

@Option(names = ["-d", "--dry-run"], description = 'Dry run mode: only print changes to make')
@Field boolean dryRun;

@Field final String JIRA_REST_URL = 'https://ticket.opower.com/rest/api/latest'
@Field final JsonSlurper json = new JsonSlurper()
@Field final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

@Field final Integer MAX_RESULTS = 100
// TODO look up custom field for "UGBU Scrum Team" and "Feature Link"
@Field final String scrumTeamFieldName = 'customfield_15751'
@Field final String targetDeliveryDateFieldName = 'customfield_14670'
@Field final String sprintFieldName = 'customfield_11450'

@Field final Map<String, String> teamLabelFieldMapping = [
  'team#DSM-HL' : 'Hotline',
  'team#DSM-JEDI' : 'Jedi',
  'team#DSM-MOONZAI' : 'Moonzai',
  'team#DSM-RA' : 'Red Alerts',
  'team#DSM-ALPHA' : 'Team Alpha',
  'team#DSM-TLW' : 'The Lights Watch',
  'team#DSM-PATHFINDER' : 'Pathfinder'
]

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

  response.body() ? json.parseText(response.body()) : ''
}

def executeJql(String jql, Closure callback) {
  println "Executing Query: ${jql}"
  def request = [:]
  request.jql = jql
  request.maxResults = MAX_RESULTS
  request.fields = [ 'key', 'summary', 'issuetype', 'labels', scrumTeamFieldName, targetDeliveryDateFieldName, sprintFieldName, 'fixVersions', 'created', 'duedate', 'status' ]
  request.expand = [ 'renderedFields' ]

  for (Integer startAt = 0, total = 1; startAt < total; startAt += MAX_RESULTS) {
    request.startAt = startAt
    def response = http '/search', 'POST', HttpRequest.BodyPublishers.ofString("${toJson(request)}")
    total = response.total

    println "Results found: ${total}"
    callback(response)
  }
}

def parseResults(queryResponse) {
  queryResponse.issues.collect { issue ->
    def result = [:]
    result.key = issue.key
    result.summary = issue.fields.summary
    result.type = issue.fields.issuetype.name
    result.due = issue.fields.duedate
    result.status = issue.fields.status.name
    result.team = issue.fields[scrumTeamFieldName]?.value
    result.labelTeam = issue.fields.labels.findResult { teamLabelFieldMapping[it] }
    result.fixVersions = issue.fields.fixVersions[0]?.name
    def sprintFixVersion = issue.fields[sprintFieldName]?.join(',') =~ /name=DSM\s(\d{2}\.\d{1,2})/
    result.sprint = sprintFixVersion.size() > 0 ? sprintFixVersion[0][1] : null
    result
  }
}

def syncValues(feature) {
  def update = [ update: [:]]

  if (feature.fixVersions != feature.sprint) {
    feature.sprint ?
      update.update << [ fixVersions: [[set: [[ name: feature.sprint ]] ]] ] :
      update.update << [ fixVersions: [[set: [ ] ]] ]
  }
  if (feature.labelTeam && feature.team != feature.labelTeam) {
    def scrumTeamMap = [:]
    scrumTeamMap[scrumTeamFieldName] = [ ['set': [ 'value': feature.labelTeam ]]]
    update.update << scrumTeamMap
  }

  if (!update.update.isEmpty()) {
    if (dryRun) {
      println "Updating ${feature.key}: ${toJson(update)}"
    }
    else {
      def response = http "/issue/${feature.key}", 'PUT', HttpRequest.BodyPublishers.ofString("${toJson(update)}")
      println "successfully updated ${feature.key}"
    }
  }
}

println "Validating credentials"
try {
  AuthHolder.initialize(username, password)

  http '/mypermissions'

  println "Authentication success!"

  executeJql("project = DSM AND type = Feature AND status != Closed AND \"Target Delivery Date\" ~ ${increment}", response -> {
    parseResults(response).each { result -> syncValues(result) }
  })
}
catch (Exception e) {
  println "${e.message}"
}
return