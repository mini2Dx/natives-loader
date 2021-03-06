/*******************************************************************************
 * Copyright 2011 See LIBGDX_AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.mini2Dx.natives;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads correct native libraries based on the current OS. Note that iOS
 * libraries must be statically linked.<br />
 * <br />
 * <em>Based on LibGDX's SharedLibraryLoader implementation</em>
 */
public class SharedLibraryLoader {
	private static final Map<String, File> LOADED_LIBRARIES = new ConcurrentHashMap<String, File>();

	private String nativesJar;

	public SharedLibraryLoader() {
	}

	/**
	 * Fetches the natives from the given natives jar file. Used for testing a
	 * shared lib on the fly.
	 * 
	 * @param nativesJar
	 */
	public SharedLibraryLoader(String nativesJar) {
		this.nativesJar = nativesJar;
	}

	/** Returns a CRC of the remaining bytes in the stream. */
	public String crc(InputStream input) {
		if (input == null)
			throw new IllegalArgumentException("input cannot be null.");
		CRC32 crc = new CRC32();
		byte[] buffer = new byte[4096];
		try {
			while (true) {
				int length = input.read(buffer);
				if (length == -1)
					break;
				crc.update(buffer, 0, length);
			}
		} catch (Exception ex) {
			if (input != null) {
				try {
					input.close();
				} catch (Exception e) {
				}
			}
		}
		return Long.toString(crc.getValue(), 16);
	}

	/**
	 * Maps a platform independent library name to a platform dependent name.
	 * <br />
	 * <br />
	 * Example: For libraryName 'yoga' it would loaded the following:<br />
	 * <ul>
	 * <li><strong>Windows x86:</strong> yoga.dll</li>
	 * <li><strong>Windows x86_64:</strong> yoga64.dll</li>
	 * <li><strong>Unix x86:</strong> libyoga.so</li>
	 * <li><strong>Unix x86_64:</strong> libyoga64.so</li>
	 * <li><strong>Unix arm 32 bit:</strong> libyogaarmABI.so</li>
	 * <li><strong>Unix arm 64 bit:</strong> libyogaarmABI64.so</li>
	 * <li><strong>Mac x86:</strong> libyoga.dylib</li>
	 * <li><strong>Mac x86_64:</strong> libyoga64.dylib</li>
	 * </ul>
	 * 
	 * @param libraryName
	 *            The name of the library to load
	 */
	public String mapLibraryName(String libraryName) {
		if (OsInformation.isWindows())
			return libraryName + (OsInformation.is64Bit() ? "64.dll" : ".dll");
		if (OsInformation.isUnix())
			return "lib" + libraryName + (OsInformation.isArm() ? "arm" + OsInformation.getAbi() : "")
					+ (OsInformation.is64Bit() ? "64.so" : ".so");
		if (OsInformation.isMac())
			return "lib" + libraryName + (OsInformation.is64Bit() ? "64.dylib" : ".dylib");
		return libraryName;
	}

	/**
	 * Loads a shared library for the platform the application is running on.
	 * Autodetects the appropriate libary filename to load.
	 * 
	 * @param libraryName
	 *            The platform independent library name. See
	 *            {@link #mapLibraryName(String)}
	 * @return The {@link File} where the library was extracted to and loaded
	 *         from or null if it was loaded via a different method (e.g. on iOS
	 *         and Android it is tied to the app)
	 */
	public File load(String libraryName) {
		return load(libraryName, mapLibraryName(libraryName));
	}

	/**
	 * Loads a shared library for the platform the application is running on.
	 * 
	 * @param libraryName
	 *            The platform independent library name. See
	 *            {@link #mapLibraryName(String)}
	 * @param libraryFilename
	 *            The filename for the library for this OS
	 * @return The {@link File} where the library was extracted to and loaded
	 *         from or null if it was loaded via a different method (e.g. on iOS
	 *         and Android it is tied to the app)
	 */
	public File load(String libraryName, String libraryFilename) {
		// in case of iOS, things have been linked statically to the executable,
		// bail out.
		if (OsInformation.isIOS())
			return null;

		synchronized (SharedLibraryLoader.class) {
			if (isLoaded(libraryName))
				return LOADED_LIBRARIES.get(libraryName);
			try {
				if (OsInformation.isAndroid()) {
					System.loadLibrary(libraryFilename);
					setLoaded(libraryName, null);
				} else {
					setLoaded(libraryName, loadFile(libraryFilename));
				}
			} catch (Throwable ex) {
				throw new RuntimeException(
						"Couldn't load shared library '" + libraryFilename + "' for target: "
								+ System.getProperty("os.name") + (OsInformation.is64Bit() ? ", 64-bit" : ", 32-bit"),
						ex);
			}
		}
		return LOADED_LIBRARIES.get(libraryName);
	}

	private InputStream readFile(String path) {
		if (nativesJar == null) {
			InputStream input = SharedLibraryLoader.class.getResourceAsStream("/" + path);
			if (input != null) {
				return input;
			}
			input = SharedLibraryLoader.class
					.getResourceAsStream(OsInformation.getOs().getFallbackLibraryLocation() + path);
			if (input != null) {
				return input;
			}
			throw new RuntimeException("Unable to read file for extraction: " + path);
		}

		// Read from JAR.
		try {
			ZipFile file = new ZipFile(nativesJar);
			ZipEntry entry = file.getEntry(path);
			if (entry == null)
				throw new RuntimeException("Couldn't find '" + path + "' in JAR: " + nativesJar);
			return file.getInputStream(entry);
		} catch (IOException ex) {
			throw new RuntimeException("Error reading '" + path + "' in JAR: " + nativesJar, ex);
		}
	}

	/**
	 * Extracts the specified file to the specified directory if it does not
	 * already exist or the CRC does not match. If file extraction fails and the
	 * file exists at java.library.path, that file is returned.
	 * 
	 * @param sourcePath
	 *            The file to extract from the classpath or JAR.
	 * @param dirName
	 *            The name of the subdirectory where the file will be extracted.
	 *            If null, the file's CRC will be used.
	 * @return The extracted file.
	 */
	public File extractFile(String sourcePath, String dirName) throws IOException {
		try {
			String sourceCrc = crc(readFile(sourcePath));
			if (dirName == null)
				dirName = sourceCrc;

			File extractedFile = getExtractedFile(dirName, new File(sourcePath).getName());
			if (extractedFile == null) {
				extractedFile = getExtractedFile(UUID.randomUUID().toString(), new File(sourcePath).getName());
				if (extractedFile == null)
					throw new RuntimeException(
							"Unable to find writable path to extract file. Is the user home directory writable?");
			}
			return extractFile(sourcePath, sourceCrc, extractedFile);
		} catch (RuntimeException ex) {
			// Fallback to file at java.library.path location, eg for applets.
			File file = new File(System.getProperty("java.library.path"), sourcePath);
			if (file.exists())
				return file;
			throw ex;
		}
	}

	/**
	 * Extracts the specified file into the temp directory if it does not
	 * already exist or the CRC does not match. If file extraction fails and the
	 * file exists at java.library.path, that file is returned.
	 * 
	 * @param sourcePath
	 *            The file to extract from the classpath or JAR.
	 * @param dir
	 *            The location where the extracted file will be written.
	 */
	public void extractFileTo(String sourcePath, File dir) throws IOException {
		extractFile(sourcePath, crc(readFile(sourcePath)), new File(dir, new File(sourcePath).getName()));
	}

	/**
	 * Returns a path to a file that can be written. Tries multiple locations
	 * and verifies writing succeeds.
	 * 
	 * @return null if a writable path could not be found.
	 */
	private File getExtractedFile(String dirName, String fileName) {
		// Temp directory with username in path.
		File idealFile = new File(System.getProperty("java.io.tmpdir") + "/natives-loader"
				+ System.getProperty("user.name") + "/" + dirName, fileName);
		if (canWrite(idealFile))
			return idealFile;

		// System provided temp directory.
		try {
			File file = File.createTempFile(dirName, null);
			if (file.delete()) {
				file = new File(file, fileName);
				if (canWrite(file))
					return file;
			}
		} catch (IOException ignored) {
		}

		// User home.
		File file = new File(System.getProperty("user.home") + "/.natives-loader/" + dirName, fileName);
		if (canWrite(file))
			return file;

		// Relative directory.
		file = new File(".temp/" + dirName, fileName);
		if (canWrite(file))
			return file;

		// We are running in the OS X sandbox.
		if (System.getenv("APP_SANDBOX_CONTAINER_ID") != null)
			return idealFile;

		return null;
	}

	/**
	 * Returns true if the parent directories of the file can be created and the
	 * file can be written.
	 */
	private boolean canWrite(File file) {
		File parent = file.getParentFile();
		File testFile;
		if (file.exists()) {
			if (!file.canWrite() || !canExecute(file))
				return false;
			// Don't overwrite existing file just to check if we can write to
			// directory.
			testFile = new File(parent, UUID.randomUUID().toString());
		} else {
			parent.mkdirs();
			if (!parent.isDirectory())
				return false;
			testFile = file;
		}
		try {
			new FileOutputStream(testFile).close();
			if (!canExecute(testFile))
				return false;
			return true;
		} catch (Throwable ex) {
			return false;
		} finally {
			testFile.delete();
		}
	}

	private boolean canExecute(File file) {
		try {
			Method canExecute = File.class.getMethod("canExecute");
			if ((Boolean) canExecute.invoke(file))
				return true;

			Method setExecutable = File.class.getMethod("setExecutable", boolean.class, boolean.class);
			setExecutable.invoke(file, true, false);

			return (Boolean) canExecute.invoke(file);
		} catch (Exception ignored) {
		}
		return false;
	}

	private File extractFile(String sourcePath, String sourceCrc, File extractedFile) throws IOException {
		String extractedCrc = null;
		if (extractedFile.exists()) {
			try {
				extractedCrc = crc(new FileInputStream(extractedFile));
			} catch (FileNotFoundException ignored) {
			}
		}

		// If file doesn't exist or the CRC doesn't match, extract it to the
		// temp dir.
		if (extractedCrc == null || !extractedCrc.equals(sourceCrc)) {
			try {
				InputStream input = readFile(sourcePath);
				extractedFile.getParentFile().mkdirs();
				FileOutputStream output = new FileOutputStream(extractedFile);
				byte[] buffer = new byte[4096];
				while (true) {
					int length = input.read(buffer);
					if (length == -1)
						break;
					output.write(buffer, 0, length);
				}
				input.close();
				output.close();
			} catch (IOException ex) {
				throw new RuntimeException(
						"Error extracting file: " + sourcePath + "\nTo: " + extractedFile.getAbsolutePath(), ex);
			}
		}

		return extractedFile;
	}

	/**
	 * Extracts the source file and calls System.load. Attemps to extract and
	 * load from multiple locations. Throws runtime exception if all fail.
	 */
	private File loadFile(String sourcePath) {
		String sourceCrc = crc(readFile(sourcePath));

		String fileName = new File(sourcePath).getName();

		// Temp directory with username in path.
		File file = new File(System.getProperty("java.io.tmpdir") + "/natives-loader" + System.getProperty("user.name")
				+ "/" + sourceCrc, fileName);
		Throwable ex = loadFile(sourcePath, sourceCrc, file);
		if (ex == null)
			return file;

		// System provided temp directory.
		try {
			file = File.createTempFile(sourceCrc, null);
			if (file.delete() && loadFile(sourcePath, sourceCrc, file) == null)
				return file;
		} catch (Throwable ignored) {
		}

		// User home.
		file = new File(System.getProperty("user.home") + "/.natives-loader/" + sourceCrc, fileName);
		if (loadFile(sourcePath, sourceCrc, file) == null)
			return file;

		// Relative directory.
		file = new File(".temp/" + sourceCrc, fileName);
		if (loadFile(sourcePath, sourceCrc, file) == null)
			return file;

		// Fallback to java.library.path location, eg for applets.
		file = new File(System.getProperty("java.library.path"), sourcePath);
		if (file.exists()) {
			System.load(file.getAbsolutePath());
			return file;
		}

		throw new RuntimeException(ex);
	}

	/** @return null if the file was extracted and loaded. */
	private Throwable loadFile(String sourcePath, String sourceCrc, File extractedFile) {
		try {
			System.load(extractFile(sourcePath, sourceCrc, extractedFile).getAbsolutePath());
			return null;
		} catch (Throwable ex) {
			return ex;
		}
	}

	/**
	 * Sets the library as loaded, for when application code wants to handle
	 * libary loading itself.
	 */
	static private void setLoaded(String libraryName, File file) {
		LOADED_LIBRARIES.put(libraryName, file);
	}

	static public boolean isLoaded(String libraryName) {
		return LOADED_LIBRARIES.containsKey(libraryName);
	}
}
