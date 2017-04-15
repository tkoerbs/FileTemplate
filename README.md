# FileTemplate
Generate files and directories from ones with placeholders in name and content.

FileTemplate is a simple Java program.<br/>
It replaces placeholders in file/dir names as well as in file content.

It copies according files/dirs and replaces the placeholders in names and content. The original file/dir templates are not touched.

Copying a dir is always a deep copy. Placeholders in files/dirs within this deep copy are replaced recursively.

Types of placeholders (please see [placeholders-sample.properties](https://github.com/tkoerbs/FileTemplate/edit/master/placeholders-sample.properties)):
* Single value
* List
  * A comma separated list of values<br/>
    Example: Value = [Miller, Jones]<br/>
    (In a first copy of the file/dir containing the placeholder, it will be replaced with Miller, in a second copy with Jones.)
* Range
  * A range of integral numbers, possibly preceeded with leading zeros<br/>
    Example: Value = [001 - 100]<br/>
    (100 copies of the file/dir containing the placeholder will be created, using 001, 002, ..., 099 and 100 as placeholders.)

Depending on the placeholders, a file/dir can be copied multiple times.

If a placeholder was replaced in an outer file/dir/section then all occurrences inside will be replaced with exactly this value.

All files and directories containing placeholders in their name or content need to be named `<real-file-name>.filetemplate`, other file's names and content is not touched (but files are copied 1:1 when located in a filetemplate directory). When replacing placeholders, these filetemplates are automatically renamed to `<real-file-name>` by the copying process. If a file or dir named `<real-file-name>` already exists it will be removed first.

Placeholders in file/dir names as well as in file content look like this: `{{@PLACEHOLDERNAME@}}`, where PLACEHOLDERNAME can be any string. Each placeholder needs to be declared in the placholders.properties file.

If placeholders of type List or Range are used in file content without beeing used outside (on a higher level - which determines its fixed value on the lower levels) then you need to use lines containing `{{@PLACEHOLDERNAME#BEGIN@}}` and `{{@PLACEHOLDERNAME#END@}}` to declare a section that is copied multiple times (once per placeholder value). It must be lines containing only such a marker, nothing else. These lines will be removed when replacing.

It is also possible to replace a placeholder not with its value but with the ordinal (position) number of the value in the list of values for this placeholder. In this case the placeholder needs to look like this: `{{@PLACEHOLDERNAME#base 001@}}`, after the placeholder type a #, then "base" and the number used for the first value in the placeholder's vlaue list. Leading zeros are preserved.

## How do I run it?

com.intershop.filetemplate.FileTemplate is a command-line Java class with no dependencies.

Just call "java com.intershop.filetemplate.FileTemplate" from a command line. You might want to supply the -classpath option with the path to the output dir containing the compiled sources. Everything else you can read from the usage screen printed out in case of a wrong usage.

E.g.: "java -classpath D:\filetemplate\bin\classes\main com.intershop.filetemplate.FileTemplate REPLACE D:\MyTestFolder D:\placeholders-mytest.properties",
where D:\MyTestFolder contains at least one `*.filetemplate` file or dir.
