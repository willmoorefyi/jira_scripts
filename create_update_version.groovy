#!/usr/bin/env groovy
import java.net.http.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import groovy.cli.picocli.CliBuilder
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field final String JIRA_REST_URL = 'https://ticket.opower.com/rest/api/latest'
@Field final JsonSlurper json = new JsonSlurper()
@Field final DateTimeFormatter jiraDateTimeFormat = DateTimeFormatter.ofPattern("d/MMM/yy")

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

def env = System.getenv()

def cli = new CliBuilder()
cli.name = 'groovy create_update_version'
cli.usageMessage.with {
 synopsisHeading('%nA command-line tool to create or update version information for multiple JIRA projects in a single category%n')
}
cli.h(longOpt:'help', 'priont this message')
cli.u(type: String, longOpt:'user', defaultValue: env.USER, 'The JIRA Username, defaults to $USER. Required')
cli.p(type: String, longOpt:'password', defaultValue: env.PASSWORD, 'The JIRA password, defaults to $PASSWORD. Required')
cli.c(type: String, longOpt:'category', defaultValue: env.JIRA_CATEGORY, 'The JIRA Category to update project versions within, defaults to $JIRA_CATEGORY. Required')

cli.n(type: String, longOpt:'name', 'The version name to create or update. Required')
cli.s(type: LocalDate, longOpt:'start', 'The start date for the version. Required')
cli.r(type: LocalDate, longOpt:'release', 'The release date for the version. Required')

opts = cli.parse(args)

if (opts.h || !opts.u || !opts.p || !opts.c || !opts.n || !opts.s || !opts.r ) {
  cli.usage()
  return 1
}

final String versionName = opts.n
final String versionStart = opts.s.format(jiraDateTimeFormat)
final String versionRelease = opts.r.format(jiraDateTimeFormat)

println "Validating credentials"
try {
  AuthHolder.initialize(opts.u, opts.p)

  http '/mypermissions'

  println "Authentication success!"

  def projects = http('/project').findAll { it.projectCategory?.name == opts.c }
  println "found '${opts.c}' category projects: ${projects*.key}"

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