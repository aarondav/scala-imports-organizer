#### Goal
This is a plugin for IntelliJ which supplements the Scala language support by providing best-effort import organization. This feature is similar to the native IntelliJ Java import organization.

#### Installation
Download `scala-import-organizer.jar` and place it into your IntelliJ's `plugins/` folder. (Or install within IntelliJ from Files -> Settings -> Plugins -> Install plugin from disk.) Restart IntelliJ and make sure the plugin is enabled from the Plugins settings.

#### Usage
By default, simply use `Ctrl + Shift + O` on Linux/Windows or `Command + Shift + O` on Macs within a Scala file, or find the command under Tools -> Organize Scala Imports.

#### Configuration
Configure the plugin in File -> Settings -> Code Style -> Scala Imports Organizer. The syntax for specifying import style should be clear from the defaults. Don't try anything fancy, it's probably not supported.

#### Pitfalls
The major issue with organizing imports in Scala is that imports may not be commutative. For example, if your imports look like this:

    import java.awt
    import awt.Button

then reordering them would not be correct. This plugin assumes imports may be ordered arbitrarily, so it will not work if your file is screwed up enough to use non-commutative imports.

#### Features
This plugin does one thing and it does it alright. It will, on command, organize your imports based on the provided configuration. If it does anything else, it is a bug.
