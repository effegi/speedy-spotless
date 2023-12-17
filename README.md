# Speedy Spotless

For easy formatting of staged changes. Inspired by [pretty-quick](https://github.com/azz/pretty-quick) and the now archived [commitd/speedy-spotless](https://github.com/commitd/speedy-spotless) plugin.

It includes `apply` and `check` goals from Spotless Maven Plugin but also includes the new goal `staged` to trigger the formatting of files staged in Git.

It can therefore be useda as a 100% drop-in replacement of `com.diffplug.spotless:spotless-maven-plugin`, with the added benefit of not having
to duplicate the spotless configuration, and provides the most robust handling of staged files I've come across so far. 
`<ratchetFrom>` does help, but partially staged files still cause issues and will result in a messed up commit.

Works with Java 11+. For java 8, use version up to 0.1.9.

## Installation

Speedy Spotless supports the exact same configuration options as Spotless Maven Plugin.

```xml

<build>
    <plugins>
      <plugin>
        <groupId>me.effegi</groupId>
        <artifactId>speedy-spotless-maven-plugin</artifactId>
        <version>0.1.10</version>
        <configuration>
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

- Spotless's `spotlessFiles` option is ignored.

## Building

```
# Building the maven plugin
mvn clean package

# Installing the maven plugin
mvn clean install -DskipTests
```

## Deploying to Maven Central

```
# Required on macOS
GPG_TTY=$(tty)
export GPG_TTY

# Setup GPG, maven settings.xml

mvn clean deploy -P release
```
