#!/usr/bin/env groovy
import java.net.http.*
import java.text.NumberFormat;
import java.util.Locale;

import groovy.cli.picocli.CliBuilder
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field


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
cli.name = 'groovy qru_retrospective_dump'
cli.usageMessage.with {
    synopsisHeading('%nA Command-line tool to dump results for QRU post-quarterly results%n')
}
cli.h(longOpt:'help', 'print this message')
cli.u(type: String, longOpt:'user', defaultValue: env.USER, 'The JIRA Username, defaults to $USER. Required')
cli.p(type: String, longOpt:'password', defaultValue: env.PASSWORD, 'The JIRA password, defaults to $PASSWORD. Required')

opts = cli.parse(args)

if (opts.h || !opts.u || !opts.p) {
    cli.usage()
    return 1
}

println "Validating credentials"
try {
    AuthHolder.initialize(opts.u, opts.p)

    http '/mypermissions'

    println "Authentication success!"

    def searchBody = [:]
    searchBody.jql = '''\
category = DSM AND issuetype = Feature  AND "UGBU Scrum Team" != AP-BURLAKI  AND ("Committed Fix Version" ~ "\\"PP 20.0\\"" OR "Committed Fix Version" ~ "\\"PP 20.1\\""  OR "Committed Fix Version" ~ "\\"PP 20.2\\"" OR "Committed Fix Version" ~ "\\"PP 19.14\\"")  and fixVersion NOT IN (19.14, 19.13, 19.12) order by "UGBU Scrum Team", fixVersion 
'''
    searchBody.startAt = 0
    searchBody.maxResults = 500
    searchBody.fields = [ 'status', 'key', 'Summary', 'reporter', 'customfield_15751', 'customfield_14670', 'fixVersions', 'customfield_16851' ]

    def results = http('/search', 'POST', HttpRequest.BodyPublishers.ofString("${JsonOutput.toJson(searchBody)}"))

    //println "found '${new JsonBuilder(results).toPrettyString()}'"

    def totalIssues = results.total
    def committedIssues = 0
    def issuesCompletedInQuarter = 0
    def issuesSlippedOutOfQuarter = 0
    def issuesSlippedNotOCI = 0
    def issuesSlippedOCI = 0
    def issuesAddedMidQuarter = 0

    results.issues.each { issue ->
        def fixVersion = issue.fields.fixVersions[0].name
        def currentStatus = issue.fields.status.name
        def reporter = issue.fields.reporter.key
        def committedFixVersion = issue.fields.customfield_16851.split(/\n/)
                .minus("")
                .findAll { val -> !val.contains('||') }
                .collect { val -> val.split(/\|/) - '' }
                .inject([:]) { acc, val -> acc << [(((val[0].trim()) =~ /\d{2,}\.\d{1,2}/)?.getAt(0)): val[1].trim()] }
                .findAll { elem -> elem.key == '19.14' || elem.key =~ /20.\d+/ }

        println "Processing issue: ${issue.key} with status ${currentStatus} committed fix versions: ${committedFixVersion}"

        currentStatus == 'Closed' ? issuesCompletedInQuarter++ : issuesSlippedOutOfQuarter++
        committedFixVersion*.key.any { it == '19.14' || it == '20.0' } ? committedIssues++ : issuesAddedMidQuarter++
        def allFixVersions = committedFixVersion*.value.toUnique()
        if (allFixVersions.size() > 1 || allFixVersions[0] != fixVersion) {
            reporter == 'siddheshwar.singh' || reporter == 'madhav.bhogaraju' ? issuesSlippedOCI++ : issuesSlippedNotOCI++
        }
    }

    final NumberFormat format = NumberFormat.getPercentInstance(Locale.US);

    println "Total features: ${totalIssues}"
    println "Commited Features: total ${committedIssues}, % of total: ${format.format(committedIssues/totalIssues)}"
    println "Feature added mid-quarter: total ${issuesAddedMidQuarter}, % of total: ${format.format(issuesAddedMidQuarter/totalIssues)}"
    println "Features Completed In Quarter: total ${issuesCompletedInQuarter}, % of total: ${format.format(issuesCompletedInQuarter/totalIssues)}"
    println "Features slipped out of Quarter: total ${issuesSlippedOutOfQuarter}, % of total: ${format.format(issuesSlippedOutOfQuarter/totalIssues)}"
    println "Features completed but slipped, not OCI: total ${issuesSlippedNotOCI}, % of total: ${format.format(issuesSlippedNotOCI/totalIssues)}"
    println "OCI Features completed but slipped: total ${issuesSlippedOCI}, % of total: ${format.format(issuesSlippedOCI/totalIssues)}"

}
catch (Exception e) {
    println "${e.message}"
}
return

