#!/usr/bin/env groovy
import java.net.http.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
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

  def request = [:]
  request.jql = "category = DSM AND type in (\"user story\", bug, task) AND updated > -${daysBack}d AND \"Feature Link\" is EMPTY"
  request.startAt = 0
  request.maxResults = 1
  request.fields = [ 'key', 'summary', 'UGBU Scrum Team' ]
  request.expand = [ 'changelog' ]

  final Instant dateFilter = LocalDate.now().minusDays(daysBack).atStartOfDay(ZoneId.systemDefault()).toInstant()
  final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  def issuesNoFeature = http '/search', 'POST', HttpRequest.BodyPublishers.ofString("${toJson(request)}")

  issuesNoFeature.issues.each { issue -> 
    def historyEntries = issue.changelog.histories.findAll { entry -> ZonedDateTime.parse(entry.created, formatter).toInstant() > dateFilter }
    println "history entries: ${prettyPrint(toJson(historyEntries))}"
  }

}
catch (Exception e) {
  println "${e.message}"
}
return
