JIRA Scripts
============

A collection of useful JIRA scripts.


# Scripts

create_update_version.groovy

A command-line tool to create or update version information for multiple JIRA projects in a single category

* Tested with: Groovy 2.5.8, Java 11

Usage: `groovy create_update_version -h`

Examples:

```
groovy create_update_version.groovy --name=20.6 --start='2020-05-18' --release='2020-06-28'
```

### Notes

This script requires you trust the opower certificate.  You must export the certificate (e.g. by visiting `ticket.opower.com` in a browser and clicking on the lock, saving the certificate file displayed) and import it into your `cacerts` keystore in the Java library.  Example (Mac, Java 11):

```
/Library/Java/JavaVirtualMachines/openjdk-11.0.2.jdk/Contents/Home/bin/keytool -import -alias ticket.opower.com -keystore /Library/Java/JavaVirtualMachines/openjdk-11.0.2.jdk/Contents/Home/lib/security/cacerts -file ~/ticket.opower.com.cer
```
