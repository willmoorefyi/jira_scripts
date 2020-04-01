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
    def committedIssues = []
    def issuesCompletedInQuarter = []
    def issuesSlippedOutOfQuarter = []
    def issuesSlippedNotOCI = []
    def issuesSlippedOCI = []
    def issuesAddedMidQuarter = []
    def issuesLaunching = []

    results.issues.each { issue ->
        def fixVersion = issue.fields.fixVersions[0].name
        def currentStatus = issue.fields.status.name
        def reporter = issue.fields.reporter.key
        def ugbuScrumTeam = issue.fields.customfield_15751.value
        def committedFixVersion = issue.fields.customfield_16851.split(/\n/)
                .minus("")
                .findAll { val -> !val.contains('||') }
                .collect { val -> val.split(/\|/) - '' }
                .inject([:]) { acc, val -> acc << [(((val[0].trim()) =~ /\d{2,}\.\d{1,2}/)?.getAt(0)): val[1].trim()] }
                .findAll { elem -> elem.key == '19.14' || elem.key =~ /20.\d+/ }

        def classifiers = [
                committedIssue: 0,
                issueCompletedInQuarter: 0,
                issueSlippedOutOfQuarter: 0,
                issueSlippedNotOCI: 0,
                issueSlippedOCI: 0,
                issueAddedMidQuarter: 0,
                issueLaunching: 0
        ]

        //println "Processing issue: ${issue.key} with status ${currentStatus} committed fix versions: ${committedFixVersion}"

        if (currentStatus == 'Closed') {
            issuesCompletedInQuarter << issue
            classifiers.issueCompletedInQuarter = 1
        } else if (currentStatus == 'Launching' && fixVersion == '20.3' && committedFixVersion*.value[-1] == '20.3') {
            issuesLaunching << issue
            classifiers.issueLaunching = 1
        } else {
            issuesSlippedOutOfQuarter << issue
            classifiers.issueSlippedOutOfQuarter = 1
        }
        if (committedFixVersion*.key.any { it == '19.14' || it == '20.0' }) {
            committedIssues << issue
            classifiers.committedIssue = 1
        } else {
            issuesAddedMidQuarter << issue
            classifiers.issueAddedMidQuarter = 1
        }
        def allFixVersions = committedFixVersion*.value.toUnique()
        if (allFixVersions.size() > 1 || allFixVersions[0] != fixVersion) {
            if (reporter == 'siddheshwar.singh' || reporter == 'madhav.bhogaraju') {
                issuesSlippedOCI << issue
                classifiers.issueSlippedOCI = 1
            } else {
                issuesSlippedNotOCI << issue
                classifiers.issueSlippedNotOCI = 1
            }
        }

        println "${issue.key},${currentStatus},${ugbuScrumTeam},${fixVersion},\"${committedFixVersion}\"," +
                "${classifiers.committedIssue},${classifiers.issueAddedMidQuarter},${classifiers.issueCompletedInQuarter}," +
                "${classifiers.issueSlippedOutOfQuarter},${classifiers.issueSlippedNotOCI},${classifiers.issueSlippedOCI}," +
                "${classifiers.issueLaunching}"
    }

    final NumberFormat format = NumberFormat.getPercentInstance(Locale.US);

    println "Total features: ${totalIssues}"
    println "Commited Features: total ${committedIssues.size()}, % of total: ${format.format(committedIssues.size()/totalIssues)}"
    println "Feature added mid-quarter: total ${issuesAddedMidQuarter.size()}, % of total: ${format.format(issuesAddedMidQuarter.size()/totalIssues)}"
    println "Features Completed In Quarter: total ${issuesCompletedInQuarter.size()}, % of total: ${format.format(issuesCompletedInQuarter.size()/totalIssues)}"
    println "Features slipped out of Quarter: total ${issuesSlippedOutOfQuarter.size()}, % of total: ${format.format(issuesSlippedOutOfQuarter.size()/totalIssues)}"
    println "Features completed but slipped, not OCI: total ${issuesSlippedNotOCI.size()}, % of total: ${format.format(issuesSlippedNotOCI.size()/totalIssues)}"
    println "OCI Features completed but slipped: total ${issuesSlippedOCI.size()}, % of total: ${format.format(issuesSlippedOCI.size()/totalIssues)}"
    println "Issues launching in 20.3: total ${issuesLaunching.size()}, % of total ${format.format(issuesLaunching.size() /totalIssues)}"

}
catch (Exception e) {
    println "${e.message}"
}
return

