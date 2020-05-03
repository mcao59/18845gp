all: FileData.class RemoteFile.class Server.class ProxyCache.class Proxy.class Lease.class FileReadData.java

%.class: %.java
	javac $<

clean:
	rm -f *.class
