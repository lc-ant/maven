package net.lecousin.ant.maven.artifact;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

public class DirectoryArtifactReader implements ArtifactReader {

	private File dir;
	
	public DirectoryArtifactReader(File dir) {
		this.dir = dir;
	}
	
	@Override
	public boolean hasFile(String path) {
		return new File(dir, path).isFile();
	}
	
	@Override
	public InputStream openFile(String path) throws FileNotFoundException {
		return new FileInputStream(new File(dir, path));
	}

	@Override
	public void close() throws Exception {
		// nothing
	}
	
}
