/*
 * Copyright 2021 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.spotless.maven.incremental;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class FileIndexTest {

	private final FileIndexConfig config = mock(FileIndexConfig.class);
	private final Log log = mock(Log.class);

	private Path tempDir;

	@BeforeEach
	void beforeEach(@TempDir Path tempDir) throws Exception {
		this.tempDir = tempDir;

		Path projectDir = tempDir.resolve("my-project");
		Files.createDirectory(projectDir);
		when(config.getProjectDir()).thenReturn(projectDir);

		Path indexFile = projectDir.resolve("target").resolve("spotless-index");
		when(config.getIndexFile()).thenReturn(indexFile);

		when(config.getPluginFingerprint()).thenReturn(PluginFingerprint.from("foo"));
	}

	@Test
	void readFallsBackToEmptyIndexWhenIndexFileDoesNotExist() {
		FileIndex index = FileIndex.read(config, log);

		assertThat(index.size()).isZero();
		verify(log).info("Index file does not exist. Fallback to an empty index");
	}

	@Test
	void readFallsBackToEmptyIndexWhenIndexFileIsEmpty() throws Exception {
		writeIndexFile();

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.size()).isZero();
		verify(log).info("Index file is empty. Fallback to an empty index");
	}

	@Test
	void readFallsBackToEmptyIndexOnFingerprintMismatch() throws Exception {
		createSourceFilesAndWriteIndexFile("bar", "source1.txt", "source2.txt");

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.size()).isZero();
		verify(log).info("Fingerprint mismatch in the index file. Fallback to an empty index");
	}

	@Test
	void readFallsBackToEmptyIndexWhenIncorrectSeparator() throws Exception {
		createSourceFile("source.txt");
		writeIndexFile(config.getPluginFingerprint().value(), "source.txt|" + Instant.now());

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.size()).isZero();
		ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
		verify(log).warn(eq("Error reading the index file. Fallback to an empty index"), errorCaptor.capture());
		assertThat(errorCaptor.getValue()).hasMessageContaining("Incorrect index file. No separator found");
	}

	@Test
	void readFallsBackToEmptyIndexWhenUnparseableTimestamp() throws Exception {
		createSourceFile("source.txt");
		writeIndexFile(config.getPluginFingerprint().value(), "source.txt 12345-bad-instant");

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.size()).isZero();
		ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
		verify(log).warn(eq("Error reading the index file. Fallback to an empty index"), errorCaptor.capture());
		assertThat(errorCaptor.getValue()).hasMessageContaining("Incorrect index file. Unable to parse last modified time");
	}

	@Test
	void readEmptyIndex() throws Exception {
		writeIndexFile("foo");

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.size()).isZero();
		verifyNoInteractions(log);
	}

	@Test
	void readIndexWithSingleEntry() throws Exception {
		Path sourceFile = createSourceFilesAndWriteIndexFile("foo", "source.txt").get(0);

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.size()).isOne();
		assertThat(index.getLastModifiedTime(sourceFile)).contains(Files.getLastModifiedTime(sourceFile).toInstant());
		verifyNoInteractions(log);
	}

	@Test
	void readIndexWithMultipleEntries() throws Exception {
		List<Path> sourceFiles = createSourceFilesAndWriteIndexFile("foo", "source1.txt", "source2.txt", "source3.txt");

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.size()).isEqualTo(3);
		for (Path sourceFile : sourceFiles) {
			assertThat(index.getLastModifiedTime(sourceFile)).contains(Files.getLastModifiedTime(sourceFile).toInstant());
		}
		verifyNoInteractions(log);
	}

	@Test
	void writeIndexWithoutUpdatesDoesNotUpdateTheFile() throws Exception {
		createSourceFilesAndWriteIndexFile("foo", "source.txt");
		FileTime modifiedTimeBeforeRead = Files.getLastModifiedTime(config.getIndexFile());

		FileIndex index = FileIndex.read(config, log);
		FileTime modifiedTimeAfterRead = Files.getLastModifiedTime(config.getIndexFile());

		index.write();
		FileTime modifiedTimeAfterWrite = Files.getLastModifiedTime(config.getIndexFile());

		assertThat(modifiedTimeAfterRead).isEqualTo(modifiedTimeBeforeRead);
		assertThat(modifiedTimeAfterWrite).isEqualTo(modifiedTimeAfterRead);
	}

	@Test
	void writeIndexContainingUpdates() throws Exception {
		createSourceFilesAndWriteIndexFile("foo", "source1.txt", "source2.txt");
		Path sourceFile3 = createSourceFile("source3.txt");
		Path sourceFile4 = createSourceFile("source4.txt");
		Instant modifiedTime3 = Instant.now();
		Instant modifiedTime4 = Instant.now().plusSeconds(42);

		FileIndex index1 = FileIndex.read(config, log);
		index1.setLastModifiedTime(sourceFile3, modifiedTime3);
		index1.setLastModifiedTime(sourceFile4, modifiedTime4);
		index1.write();

		FileIndex index2 = FileIndex.read(config, log);
		assertThat(index2.getLastModifiedTime(sourceFile3)).contains(modifiedTime3);
		assertThat(index2.getLastModifiedTime(sourceFile4)).contains(modifiedTime4);
	}

	@Test
	void writeIndexWhenParentDirDoesNotExist() throws Exception {
		assertThat(config.getIndexFile().getParent()).doesNotExist();
		FileIndex index1 = FileIndex.read(config, log);
		Path sourceFile = createSourceFile("source.txt");
		Instant modifiedTime = Instant.now();
		index1.setLastModifiedTime(sourceFile, modifiedTime);

		index1.write();

		assertThat(config.getIndexFile().getParent()).exists();
		FileIndex index2 = FileIndex.read(config, log);
		assertThat(index2.getLastModifiedTime(sourceFile)).contains(modifiedTime);
	}

	@Test
	void deleteNonExistingIndex() {
		FileIndex.delete(config, log);

		verifyNoInteractions(log);
	}

	@Test
	void deleteExistingIndex() throws Exception {
		createSourceFilesAndWriteIndexFile("foo", "source1.txt", "source2.txt");
		assertThat(config.getIndexFile()).exists();

		FileIndex.delete(config, log);

		verify(log).info(contains("Deleted the index file"));
		assertThat(config.getIndexFile()).doesNotExist();
	}

	@Test
	void getLastModifiedTimeReturnsEmptyOptionalForNonProjectFile() throws Exception {
		createSourceFilesAndWriteIndexFile("foo", "source.txt");
		Path nonProjectDir = tempDir.resolve("some-other-project");
		Files.createDirectory(nonProjectDir);
		Path nonProjectFile = Files.createFile(nonProjectDir.resolve("some-other-source.txt"));

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.getLastModifiedTime(nonProjectFile)).isEmpty();
	}

	@Test
	void getLastModifiedTimeReturnsEmptyOptionalForUnknownFile() throws Exception {
		createSourceFilesAndWriteIndexFile("foo", "source.txt");
		Path unknownSourceFile = createSourceFile("unknown-source.txt");

		FileIndex index = FileIndex.read(config, log);

		assertThat(index.getLastModifiedTime(unknownSourceFile)).isEmpty();
	}

	@Test
	void setLastModifiedTimeThrowsForNonProjectFile() throws Exception {
		FileIndex index = FileIndex.read(config, log);
		Path nonProjectFile = Paths.get("non-project-file");

		assertThatThrownBy(() -> index.setLastModifiedTime(nonProjectFile, Instant.now())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void setLastModifiedTimeUpdatesModifiedTime() throws Exception {
		Path sourceFile = createSourceFilesAndWriteIndexFile("foo", "source.txt").get(0);
		FileIndex index = FileIndex.read(config, log);

		Optional<Instant> oldTimeOptional = index.getLastModifiedTime(sourceFile);
		assertThat(oldTimeOptional).isPresent();

		Instant newTime = Instant.now().plusSeconds(42);
		assertThat(oldTimeOptional.get()).isNotEqualTo(newTime);

		index.setLastModifiedTime(sourceFile, newTime);
		assertThat(index.getLastModifiedTime(sourceFile)).contains(newTime);
	}

	@Test
	void rewritesIndexFileThatReferencesNonExistingFile() throws Exception {
		List<Path> sourceFiles = createSourceFilesAndWriteIndexFile("foo", "source1.txt", "source2.txt");
		Path nonExistingSourceFile = config.getProjectDir().resolve("non-existing-source.txt");
		FileIndex index1 = FileIndex.read(config, log);
		assertThat(index1.size()).isEqualTo(2);

		index1.setLastModifiedTime(nonExistingSourceFile, Instant.now());
		assertThat(index1.size()).isEqualTo(3);
		index1.write();

		FileIndex index2 = FileIndex.read(config, log);
		verify(log).info("File stored in the index does not exist: " + nonExistingSourceFile.getFileName());
		assertThat(index2.size()).isEqualTo(2);
		index2.write();

		FileIndex index3 = FileIndex.read(config, log);
		assertThat(index3.size()).isEqualTo(2);
	}

	private List<Path> createSourceFilesAndWriteIndexFile(String fingerprint, String... files)
			throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add(fingerprint);

		List<Path> sourceFiles = new ArrayList<>();
		for (String file : files) {
			Path path = createSourceFile(file);
			lines.add(file + " " + Files.getLastModifiedTime(path).toInstant());
			sourceFiles.add(path);
		}

		writeIndexFile(lines.toArray(new String[0]));
		return sourceFiles;
	}

	private void writeIndexFile(String... lines) throws IOException {
		Files.createDirectory(config.getIndexFile().getParent());
		Files.createFile(config.getIndexFile());
		Files.write(config.getIndexFile(), Arrays.asList(lines), UTF_8, APPEND);
	}

	private Path createSourceFile(String name) throws IOException {
		Path file = config.getProjectDir().resolve(name);
		Files.createFile(file);
		return file;
	}
}
