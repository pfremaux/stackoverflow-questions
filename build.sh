#!/bin/bash
rm -Rf ./build/classes/java/main
find -path './src/main/*.java' -name *.java > sources.txt
javac @sources.txt -d ./build/classes/java/main
rm sources.txt

jar cf main.jar -C build/classes/java/main .
jar fum main.jar manifest.txt

echo Now run the following command:
echo java -jar main.jar