package org.example.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "chunkId", "chunkIndex", "totalChunks",
    "sourceFile", "fileType",
    "programId", "author", "dateWritten", "programDescription",
    "domain", "subDomain", "processingType",
    "division", "sectionName", "lineStart", "lineEnd",
    "sectionPurpose",
    "filesRead", "filesWritten", "filesUpdated", "filesDeleted",
    "copybooksReferenced", "entryPoints",
    "externalProgramsCalled", "paragraphsCalled",
    "keyDataFields", "businessConditions",
    "hasFileIO", "hasErrorHandling",
    "tags", "shouldEmbed", "embeddingText", "content"
})
public class FileChunk {

    private String chunkId;
    private int chunkIndex;
    private int totalChunks;

    // Source
    private String sourceFile;
    private String fileType;

    // Program identity (from IDENTIFICATION DIVISION or JOB card)
    private String programId;
    private String author;
    private String dateWritten;
    private String programDescription;

    // Domain classification
    private String domain;
    private String subDomain;
    private String processingType;

    // Structural location
    private String division;
    private String sectionName;
    private int lineStart;
    private int lineEnd;

    // Semantic metadata (derived by code analysis)
    private String sectionPurpose;
    private List<String> filesRead;
    private List<String> filesWritten;
    private List<String> filesUpdated;
    private List<String> filesDeleted;
    private List<String> copybooksReferenced;
    private List<String> entryPoints;
    private List<String> externalProgramsCalled;
    private List<String> paragraphsCalled;
    private List<String> keyDataFields;
    private List<String> businessConditions;
    private boolean hasFileIO;
    private boolean hasErrorHandling;

    private List<String> tags;
    private boolean shouldEmbed;
    private String embeddingText;
    private String content;

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getProgramId() { return programId; }
    public void setProgramId(String programId) { this.programId = programId; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getDateWritten() { return dateWritten; }
    public void setDateWritten(String dateWritten) { this.dateWritten = dateWritten; }

    public String getProgramDescription() { return programDescription; }
    public void setProgramDescription(String programDescription) { this.programDescription = programDescription; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getSubDomain() { return subDomain; }
    public void setSubDomain(String subDomain) { this.subDomain = subDomain; }

    public String getProcessingType() { return processingType; }
    public void setProcessingType(String processingType) { this.processingType = processingType; }

    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = division; }

    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public int getLineStart() { return lineStart; }
    public void setLineStart(int lineStart) { this.lineStart = lineStart; }

    public int getLineEnd() { return lineEnd; }
    public void setLineEnd(int lineEnd) { this.lineEnd = lineEnd; }

    public String getSectionPurpose() { return sectionPurpose; }
    public void setSectionPurpose(String sectionPurpose) { this.sectionPurpose = sectionPurpose; }

    public List<String> getFilesRead() { return filesRead; }
    public void setFilesRead(List<String> filesRead) { this.filesRead = filesRead; }

    public List<String> getFilesWritten() { return filesWritten; }
    public void setFilesWritten(List<String> filesWritten) { this.filesWritten = filesWritten; }

    public List<String> getFilesUpdated() { return filesUpdated; }
    public void setFilesUpdated(List<String> filesUpdated) { this.filesUpdated = filesUpdated; }

    public List<String> getFilesDeleted() { return filesDeleted; }
    public void setFilesDeleted(List<String> filesDeleted) { this.filesDeleted = filesDeleted; }

    public List<String> getCopybooksReferenced() { return copybooksReferenced; }
    public void setCopybooksReferenced(List<String> copybooksReferenced) { this.copybooksReferenced = copybooksReferenced; }

    public List<String> getEntryPoints() { return entryPoints; }
    public void setEntryPoints(List<String> entryPoints) { this.entryPoints = entryPoints; }

    public List<String> getExternalProgramsCalled() { return externalProgramsCalled; }
    public void setExternalProgramsCalled(List<String> externalProgramsCalled) { this.externalProgramsCalled = externalProgramsCalled; }

    public List<String> getParagraphsCalled() { return paragraphsCalled; }
    public void setParagraphsCalled(List<String> paragraphsCalled) { this.paragraphsCalled = paragraphsCalled; }

    public List<String> getKeyDataFields() { return keyDataFields; }
    public void setKeyDataFields(List<String> keyDataFields) { this.keyDataFields = keyDataFields; }

    public List<String> getBusinessConditions() { return businessConditions; }
    public void setBusinessConditions(List<String> businessConditions) { this.businessConditions = businessConditions; }

    public boolean isHasFileIO() { return hasFileIO; }
    public void setHasFileIO(boolean hasFileIO) { this.hasFileIO = hasFileIO; }

    public boolean isHasErrorHandling() { return hasErrorHandling; }
    public void setHasErrorHandling(boolean hasErrorHandling) { this.hasErrorHandling = hasErrorHandling; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public boolean isShouldEmbed() { return shouldEmbed; }
    public void setShouldEmbed(boolean shouldEmbed) { this.shouldEmbed = shouldEmbed; }

    public String getEmbeddingText() { return embeddingText; }
    public void setEmbeddingText(String embeddingText) { this.embeddingText = embeddingText; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}