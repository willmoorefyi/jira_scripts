#!/usr/bin/env groovy
import groovy.cli.picocli.CliBuilder
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.net.http.*

@Field final String JIRA_REST_URL = 'https://ticket.opower.com/rest/api/latest'
@Field final JsonSlurper json = new JsonSlurper()

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

def get(path) {
  HttpClient client = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .build()

  HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create("${JIRA_REST_URL}${path}"))
      .header("Accepts", "application/json")
      .header("Authorization", AuthHolder.getInstance().AUTH_HEADER)
      .build()

  HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())

  if(!response.statusCode() || response.statusCode() > 299 ) {
    throw new RuntimeException("Authentication with input username / password failed! Server returned HTTP status ${response.statusCode()}")
  } else if (response.statusCode() > 499) {
    throw new RuntimeException("JIRA server error ${response.statusCode()}, please use the UI to confirm the system is up and running")
    return 1
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
cli.u(type: String, longOpt:'user', defaultValue: env.USER, 'The JIRA Username, defaults to $USER')
cli.p(type: String, longOpt:'password', defaultValue: env.PASSWORD, 'The JIRA password, defaults to $PASSWORD')
cli.c(type: String, longOpt:'category', defaultValue: env.JIRA_CATEGORY, 'The JIRA Category to update project versions within, defaults to $JIRA_CATEGORY')

options = cli.parse(args)

if (options.h || !options.u || !options.p || !options.c) {
  cli.usage()
  return 1
}

println "Validating credentials"
try {
  AuthHolder.initialize(options.u, options.p)

  get("/mypermissions")

  println "Authentication success!"

  def projects = get("/project").findAll { it.projectCategory?.name == options.c }
  println "found '${options.c}' category projects: ${projects.key}"

}
catch (Exception e) {
  println "${e.message}"
}
return