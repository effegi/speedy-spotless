# Speedy Spotless

For easy formatting of staged changes. Inspired by [pretty-quick](https://github.com/azz/pretty-quick) and the now archived [commitd/speedy-spotless](https://github.com/commitd/speedy-spotless) plugin.

It includes `apply` and `check` goals from Spotless Maven Plugin but also includes the new goal `staged` to trigger the formatting of files staged in Git.

It can therefore be useda as a 100% drop-in replacement of `com.diffplug.spotless:spotless-maven-plugin`, with the added benefit of not having
to duplicate the spotless configuration, and provides the most robust handling of staged files I've come across so far. 
`<ratchetFrom>` does help, but partially staged files still cause issues and will result in a messed up commit.

Works with Java 17+. For java 8, use version up to 0.1.9.

## Installation

Speedy Spotless supports the exact same configuration options as Spotless Maven Plugin.

```xml

<build>
    <plugins>
      <plugin>
        <groupId>me.effegi</groupId>
        <artifactId>speedy-spotless-maven-plugin</artifactId>
        <version>0.1.13</version>
        <configuration>
          <upToDateChecking>
            <enabled>false</enabled>
          </upToDateChecking>
          <pom>
            <sortPom>
              <indentSchemaLocation>true</indentSchemaLocation>
              <expandEmptyElements>false</expandEmptyElements>
            </sortPom>
          </pom>
          <java>
            <palantirJavaFormat>
              <style>GOOGLE</style>
            </palantirJavaFormat>
            <removeUnusedImports />
          </java>
        </configuration>
      </plugin>
    </plugins>
</build>

```

You might want to use a plugin like `com.rudikershaw.gitbuildhook:git-build-hook-maven-plugin` to invoke `speedy-spotless:staged` as a pre-commit hook.

## Configuration

See [Spotless Maven Plugin](https://github.com/diffplug/spotless/tree/master/plugin-maven#applying-to-java-source) for code formatting options.

## Caveats

### Up-to-date checking must be configured

Spotless' incremental up-to-date checking identifies a build by looking up
`com.diffplug.spotless:spotless-maven-plugin` in the project. This plugin is published under
different coordinates, so with the default configuration every goal - `staged`, `apply` and
`check` alike - fails before doing any work:

```
Spotless plugin absent from the project: MavenProject: ...
```

This happens inside Spotless' own `execute()`, which is `final`, so the plugin cannot catch it
and offer a better message. Pick one of two fixes.

Disable up-to-date checking, as in the example above:

```xml
<upToDateChecking>
  <enabled>false</enabled>
</upToDateChecking>
```

Or, to keep caching, declare Spotless in `<pluginManagement>`. It is never executed from there;
it only has to be present for the lookup to resolve. Cache invalidation still works correctly,
because the fingerprint is computed from the configured formatters rather than from the declared
plugin:

```xml
<pluginManagement>
  <plugins>
    <plugin>
      <groupId>com.diffplug.spotless</groupId>
      <artifactId>spotless-maven-plugin</artifactId>
      <version>3.10.1</version>
    </plugin>
  </plugins>
</pluginManagement>
```

### Parallel builds

The `staged` goal writes to the git index, which every module in a reactor shares. Under
`mvn -T` the modules contend on `.git/index.lock` and most of them fail, having formatted the
working tree without updating the index. Run the goal without `-T`.

## Building

```
# Building the maven plugin
mvn clean package

# Installing the maven plugin
mvn clean install -DskipTests

# Running the integration tests
# Each test builds a throwaway git repository and runs the staged goal against it
# with a real Maven process, so this installs the plugin locally first.
mvn clean verify
```

## Deploying to Maven Central

```
# Required on macOS
GPG_TTY=$(tty)
export GPG_TTY

# Setup GPG, maven settings.xml

mvn clean deploy -P release
```
