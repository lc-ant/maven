package net.lecousin.ant.maven.artifact;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.apache.maven.artifact.Artifact;

public interface ArtifactReader extends AutoCloseable {

	static ArtifactReader of(Artifact a) throws IOException {
		return of(a.getFile());
	}
	
	static ArtifactReader of(File file) throws IOException {
		if (file.isDirectory())
			return new DirectoryArtifactReader(file);
		return new JarArtifactReader(file);
	}
	
	boolean hasFile(String path);
	
	InputStream openFile(String path) throws IOException;
	
}
