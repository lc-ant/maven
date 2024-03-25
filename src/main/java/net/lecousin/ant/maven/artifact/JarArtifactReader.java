package net.lecousin.ant.maven.artifact;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class JarArtifactReader implements ArtifactReader {

	private ZipFile zip;
	
	public JarArtifactReader(File file) throws IOException {
		zip = new ZipFile(file);
	}
	
	@Override
	public void close() throws Exception {
		zip.close();
	}
	
	@Override
	public boolean hasFile(String path) {
		ZipEntry entry = zip.getEntry(path);
		if (entry == null) return false;
		if (entry.isDirectory()) return false;
		return true;
	}
	
	@Override
	public InputStream openFile(String path) throws IOException {
		ZipEntry entry = zip.getEntry(path);
		if (entry == null) throw new FileNotFoundException(path);
		return zip.getInputStream(entry);
	}
	
}
