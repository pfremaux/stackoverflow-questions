@echo off
dir /s /B src\main\*.java > sources.txt
javac @sources.txt -d ./build/classes/java/main

jar cf main.jar -C build\classes\java\main .
jar fum main.jar manifest.txt
del sources.txt

echo Now run the following command:
echo java -jar main.jar