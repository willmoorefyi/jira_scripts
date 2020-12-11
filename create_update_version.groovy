#!/usr/bin/env groovy
import java.net.http.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Grab('info.picocli:picocli-groovy:4.5.2')
@GrabConfig(systemClassLoader=true)
@Command(name = "create_update_version",
        version = "0.0.1",
        mixinStandardHelpOptions = true, // add --help and --version options
        description = "A command-line tool to create or update version information for multiple JIRA projects in a single category")
@picocli.groovy.PicocliScript
import static picocli.CommandLine.*

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

@Option(names = ["-c", "--category"], description = 'The JIRA Category to update project versions within, defaults to $JIRA_CATEGORY. Required')
@Field String category = System.getenv().JIRA_CATEGORY

@Option(names = ["-n", "--name"], description = 'The version name to create or update. Required', required = true)
@Field String versionName

@Option(names = ["-s", "--start"], description = 'The start date for the version. Required', required = true)
@Field LocalDate start

@Option(names = ["-r", "--release"], description = 'The release date for the version. Required', required = true)
@Field LocalDate release

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

final String versionStart = start.format(jiraDateTimeFormat)
final String versionRelease = release.format(jiraDateTimeFormat)

println "Validating credentials"
try {
  AuthHolder.initialize(username, password)

  http '/mypermissions'

  println "Authentication success!"

  def projects = http('/project').findAll { it.projectCategory?.name == category }
  println "found '${category}' category projects: ${projects*.key}"

  projects.each { project ->
    def version = http("/project/${project.key}/versions").find { it.name == versionName }
    if (version) {
      println """
Updating version ${versionName} on project ${project.key}
  from start ${version.userStartDate} and release ${version.userReleaseDate}
  to start ${versionStart} and release ${versionRelease}
      """
      def request = [:]
      request.id = version.id
      request.name = versionName
      request.userStartDate = versionStart
      request.userReleaseDate = versionRelease

      http "/version/${version.id}", "PUT", HttpRequest.BodyPublishers.ofString("${JsonOutput.toJson(request)}")
    }
    else {
      def request = [:]
      request.name = versionName
      request.userStartDate = versionStart
      request.userReleaseDate = versionRelease
      request.projectId = project.id
      request.project = project.key

      println "Creating new version ${versionName} on project ${project.key} with start ${versionStart} and release ${versionRelease}"
      http "/version", "POST", HttpRequest.BodyPublishers.ofString("${JsonOutput.toJson(request)}")
    }
  }

}
catch (Exception e) {
  println "${e.message}"
}
return

