#!/usr/bin/env groovy
@GrabConfig(systemClassLoader=true)

// Note: This script doesn't do anything.  It was aborted because Yiqiu already created a scriptrunner task to do something similar, 
// but I'm keeping the code because iterating over users in a group may be useful in the future
import java.net.http.*
import java.text.NumberFormat;
import java.util.Locale;

@Grab('info.picocli:picocli-groovy:4.5.2')
@GrabConfig(systemClassLoader=true)
@Command(name = "update_blank_avatars",
        version = "0.0.1",
        mixinStandardHelpOptions = true, // add --help and --version options
        description = "A command-line tool to reset blank avatars")
@picocli.groovy.PicocliScript
import static picocli.CommandLine.*
import static groovy.json.JsonOutput.*

@Grab(group='org.apache.httpcomponents', module='httpclient', version='4.5.13')
import org.apache.http.client.utils.URIBuilder

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Option(names = ["-u", "--user"], description = 'The JIRA Username, defaults to $USERNAME. Required')
@Field String username = System.getenv().USERNAME

@Option(names = ["-p", "--password"], description = 'The JIRA password, defaults to $PASSWORD. Required')
@Field String password = System.getenv().PASSWORD

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
        throw new RuntimeException("Server returned HTTP status ${response.statusCode()}")
    } else if (response.statusCode() > 499) {
        throw new RuntimeException("JIRA server error ${response.statusCode()}, please use the UI to confirm the system is up and running")
    }

    json.parseText response.body()
}

def userAvatars = [];
println "Validating credentials"
try {
  AuthHolder.initialize(username, password)

  http '/mypermissions'

  println "Authentication success!"

  for(int i=0, maxResults=50;;i+=maxResults) {
    def url = new URIBuilder('/group/member')
      .addParameter('groupname','jira-users')
      .addParameter('startAt', i as String)
      .addParameter('maxResults', maxResults as String)
      .addParameter('includeInactive', 'true')
      .build()

    def response = http url

    if (response?.values) {
      userAvatars += response?.values.collect{ user -> [ (user['key']): user.avatarUrls?['48x48'] ] }
    }
    else {
      break
    }
  }

  println "found ${new JsonBuilder(userAvatars).toPrettyString()}"
}
catch (Exception e) {
  println "${e.message}"
}
return