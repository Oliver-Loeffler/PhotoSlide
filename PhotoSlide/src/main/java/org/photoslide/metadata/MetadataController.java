/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.photoslide.metadata;

import org.photoslide.MainViewController;
import org.photoslide.ThreadFactoryPS;
import org.photoslide.datamodel.MediaFile;
import org.photoslide.lighttable.LighttableController;
import com.icafe4j.image.meta.Metadata;
import com.icafe4j.image.meta.MetadataEntry;
import com.icafe4j.image.meta.MetadataType;
import com.icafe4j.image.meta.image.Comments;
import com.icafe4j.image.meta.iptc.IPTC;
import com.icafe4j.image.meta.iptc.IPTCApplicationTag;
import com.icafe4j.image.meta.iptc.IPTCDataSet;
import com.icafe4j.image.meta.iptc.IPTCTag;
import com.icafe4j.image.meta.jpeg.JpegExif;
import com.icafe4j.image.meta.xmp.XMP;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.Accordion;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.controlsfx.control.textfield.TextFields;
import org.photoslide.Utility;

/**
 *
 * @author selfemp
 */
public class MetadataController implements Initializable {
    
    private ExecutorService executor;
    private MainViewController mainController;
    private LighttableController lightController;
    private Collection<String> keywordList;
    private MediaFile actualMediaFile;
    
    private JpegExif jpegExifdata;
    private IPTC iptcdata;
    private XMP xmpdata;
    private List<String> commentsdata;
    
    @FXML
    private Accordion accordionPane;
    @FXML
    private TitledPane keywordsPane;
    @FXML
    private TitledPane metadataPane;
    @FXML
    private TitledPane quickDevPane;
    @FXML
    private TextArea keywordText;
    @FXML
    private TextField addKeywordTextField;
    @FXML
    private TextArea commentText;
    @FXML
    private TextField captionTextField;
    @FXML
    private TextField recordDateField;
    @FXML
    private AnchorPane anchorKeywordPane;
    
    private Task<Boolean> task;
    private KeywordChangeListener keywordsChangeListener;
    private CommentsChangeListener commentsChangeListener;
    private CaptionChangeListener captionChangeListener;
    @FXML
    private StackPane stackPane;
    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private Label progressLabel;
    @FXML
    private VBox progressPane;
    @FXML
    private GridPane metaDataGrid;
    
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        executor = Executors.newSingleThreadExecutor(new ThreadFactoryPS("metaDataController"));
        keywordList = FXCollections.observableArrayList();
        Platform.runLater(() -> {
            anchorKeywordPane.setDisable(true);
            accordionPane.setExpandedPane(keywordsPane);
            progressPane.setVisible(false);
        });
        keywordsChangeListener = new KeywordChangeListener();
        commentsChangeListener = new CommentsChangeListener();
        captionChangeListener = new CaptionChangeListener();
    }
    
    public void injectMainController(MainViewController mainController) {
        this.mainController = mainController;
    }
    
    public void injectLightController(LighttableController lightController) {
        this.lightController = lightController;
    }
    
    public void setSelectedFile(MediaFile file) {
        actualMediaFile = file;
        resetGUI();
        Platform.runLater(() -> {
            progressIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            progressPane.setVisible(true);
            progressLabel.setText("Loading metadata...");
        });
        task = new Task<>() {
            @Override
            protected Boolean call() throws IOException {
                readBasicMetadata(this);
                return null;
            }
        };
        task.setOnSucceeded((t) -> {
            DateTimeFormatter formatter;
            if (actualMediaFile.getRecordTime() != null) {
                recordDateField.setText(actualMediaFile.getRecordTime().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));
            }
            updateUIWithExtendedMetadata();
            anchorKeywordPane.setDisable(false);
            progressPane.setVisible(false);
            commentText.textProperty().addListener(commentsChangeListener);
            keywordText.textProperty().addListener(keywordsChangeListener);
            captionTextField.textProperty().addListener(captionChangeListener);
        });
        task.setOnFailed((t) -> {
            anchorKeywordPane.setDisable(false);
            progressPane.setVisible(false);
            keywordText.textProperty().removeListener(keywordsChangeListener);
            captionTextField.textProperty().removeListener(captionChangeListener);
            commentText.textProperty().removeListener(commentsChangeListener);
        });
        executor.submit(task);
    }
    
    public void readBasicMetadata(Task actTask) throws IOException {
        Map<MetadataType, Metadata> metadataMap = Metadata.readMetadata(actualMediaFile.getPathStorage().toFile());
        for (Map.Entry<MetadataType, Metadata> entry : metadataMap.entrySet()) {
            if (actTask.isCancelled() == false) {
                Metadata meta = entry.getValue();
                Iterator<MetadataEntry> iterator;
                switch (meta.getType()) {
                    case XMP ->
                        xmpdata = ((XMP) meta);
                    //XMP.showXMP(xmpdata);
                    case COMMENT -> {
                        if (meta instanceof Comments) {
                            commentsdata = ((Comments) meta).getComments();
                            commentsdata.forEach(comment -> {
                                commentText.appendText(comment);
                            });
                        }
                    }
                    case IPTC -> {
                        iptcdata = ((IPTC) meta);
                        iterator = meta.iterator();
                        while (iterator.hasNext()) {
                            MetadataEntry item = iterator.next();
                            switch (item.getKey()) {
                                case "Keywords" -> {
                                    Platform.runLater(() -> {
                                        keywordText.setText(item.getValue());
                                    });
                                    StringTokenizer defaultTokenizer = new StringTokenizer(item.getValue(), ";");
                                    while (defaultTokenizer.hasMoreTokens()) {
                                        String nextToken = defaultTokenizer.nextToken();
                                        keywordList.add(nextToken);
                                    }
                                    Platform.runLater(() -> {
                                        TextFields.bindAutoCompletion(addKeywordTextField, keywordList);
                                    });
                                }
                                case "Caption Abstract" ->
                                    Platform.runLater(() -> {
                                        captionTextField.setText(item.getValue());
                                        actualMediaFile.setTitle(item.getValue());
                                    });
                            }
                        }
                    }
                    case JPG_JFIF -> {
                    }
                    case EXIF -> {
                        jpegExifdata = ((JpegExif) meta);
                        iterator = meta.iterator();
                        while (iterator.hasNext()) {
                            MetadataEntry item = iterator.next();
                            if (item.getKey().equalsIgnoreCase("EXIF SubIFD")) {
                                Collection<MetadataEntry> entries = item.getMetadataEntries();
                                for (MetadataEntry e : entries) {
                                    if (e.getKey().equalsIgnoreCase("DateTime Digitized")) {
                                        Platform.runLater(() -> {
                                            recordDateField.setText(e.getValue());
                                        });
                                        if (!e.getValue().equalsIgnoreCase("")) {
                                            LocalDateTime date;
                                            DateTimeFormatter formatter;
                                            try {
                                                formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
                                                date = LocalDateTime.parse(e.getValue(), formatter);
                                            } catch (DateTimeParseException ex) {
                                                formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
                                                date = LocalDateTime.parse(e.getValue(), formatter);
                                            }
                                            actualMediaFile.setRecordTime(date);
                                        } else {
                                            actualMediaFile.setRecordTime(LocalDateTime.now());
                                        }
                                        break;
                                    }
                                }
                            }
                            if (item.getKey().equalsIgnoreCase("IFD0")) {
                                Collection<MetadataEntry> metadataEntries = item.getMetadataEntries();
                                metadataEntries.stream().forEach((mediaEntry) -> {
                                    if (mediaEntry.getKey().equalsIgnoreCase("Model")) {
                                        Platform.runLater(() -> {
                                            actualMediaFile.setCamera(mediaEntry.getValue());
                                        });
                                    }
                                });
                            }
                        }
                    }
                    case ICC_PROFILE -> {
                    }
                    default -> {
                    }
                }
                //XMP.showXMP((XMP) meta);
                //XMP.showXMP((XMP) meta);
                //XMP.showXMP((XMP) meta);
                //XMP.showXMP((XMP) meta);
                //System.out.println("type: " + meta.getType().toString());
            } else {
                break;
            }
        }
    }
    
    private void updateUIWithExtendedMetadata() {
        AtomicInteger i = new AtomicInteger(1);
        Iterator<MetadataEntry> iterator;

        //read jpge exif
        if (jpegExifdata != null) {
            iterator = jpegExifdata.iterator();
            while (iterator.hasNext()) {
                MetadataEntry item = iterator.next();
                Collection<MetadataEntry> entries = item.getMetadataEntries();
                entries.forEach(e -> {
                    Label key = new Label(e.getKey());
                    Label value = new Label(e.getValue());
                    key.setStyle("-fx-font-size:8pt;");
                    value.setStyle("-fx-font-size:8pt;");
                    metaDataGrid.addRow(i.get(), key, value);
                    i.addAndGet(1);
                });
            }
        }

        //read iptcdata exif
        if (iptcdata != null) {
            iterator = iptcdata.iterator();
            while (iterator.hasNext()) {
                MetadataEntry item = iterator.next();
                Collection<MetadataEntry> entries = item.getMetadataEntries();
                entries.forEach(e -> {
                    Label key = new Label(e.getKey());
                    Label value = new Label(e.getValue());
                    key.setStyle("-fx-font-size:8pt;");
                    value.setStyle("-fx-font-size:8pt;");
                    metaDataGrid.addRow(i.get(), key, value);
                    i.addAndGet(1);
                });
            }
        }

        //read xmpdata
        if (xmpdata != null) {
            iterator = xmpdata.iterator();
            while (iterator.hasNext()) {
                MetadataEntry item = iterator.next();
                Collection<MetadataEntry> entries = item.getMetadataEntries();
                entries.forEach(e -> {
                    Label key = new Label(e.getKey());
                    Label value = new Label(e.getValue());
                    key.setStyle("-fx-font-size:8pt;");
                    value.setStyle("-fx-font-size:8pt;");
                    metaDataGrid.addRow(i.get(), key, value);
                    i.addAndGet(1);
                });
            }
        }
        
        metaDataGrid.setDisable(false);
    }
    
    public void resetGUI() {
        Platform.runLater(() -> {
            metaDataGrid.getChildren().clear();
            metaDataGrid.setDisable(true);
        });
        keywordText.textProperty().removeListener(keywordsChangeListener);
        captionTextField.textProperty().removeListener(captionChangeListener);
        commentText.textProperty().removeListener(commentsChangeListener);
        keywordsChangeListener = new KeywordChangeListener();
        commentsChangeListener = new CommentsChangeListener();
        captionChangeListener = new CaptionChangeListener();
        keywordText.clear();
        commentText.clear();
        captionTextField.clear();
        recordDateField.clear();
        progressPane.setVisible(false);
        anchorKeywordPane.setDisable(true);
    }
    
    @FXML
    private void addKeywordAction(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            if (!keywordText.getText().equalsIgnoreCase("")) {
                String lastChar = keywordText.getText().substring(keywordText.getText().length() - 1);
                if (!lastChar.equalsIgnoreCase(";")) {
                    keywordText.appendText(";" + addKeywordTextField.getText());
                } else {
                    keywordText.appendText(addKeywordTextField.getText());
                }
            } else {
                keywordText.appendText(addKeywordTextField.getText());
            }
            addKeywordTextField.clear();
        }
    }
    
    private void saveAction(ActionEvent event) {
        FileInputStream fin;
        ByteArrayOutputStream bout = null;
        try {
            bout = new ByteArrayOutputStream();
            fin = new FileInputStream(actualMediaFile.getPathStorage().toFile());
            
            List<IPTCDataSet> iptcs = new ArrayList<>();
            StringTokenizer defaultTokenizer = new StringTokenizer(keywordText.getText(), ";");
            
            while (defaultTokenizer.hasMoreTokens()) {
                iptcs.add(new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, defaultTokenizer.nextToken()));
            }
            iptcs.add(new IPTCDataSet(IPTCApplicationTag.OBJECT_NAME, captionTextField.getText()));
            Metadata.insertIPTC(fin, bout, iptcs, true);
            
            fin.close();
            try ( OutputStream outputStream = new FileOutputStream(actualMediaFile.getPathStorage().toFile())) {
                bout.writeTo(outputStream);
            }
            bout.close();
            
            bout = new ByteArrayOutputStream();
            fin = new FileInputStream(actualMediaFile.getPathStorage().toFile());
            
            Metadata.insertComment(fin, bout, commentText.getText());
            
            fin.close();
            try ( OutputStream outputStream = new FileOutputStream(actualMediaFile.getPathStorage().toFile())) {
                bout.writeTo(outputStream);
            }
            
        } catch (IOException ex) {
            Logger.getLogger(MetadataController.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            try {
                if (bout != null) {
                    bout.close();
                }
            } catch (IOException ex) {
                Logger.getLogger(MetadataController.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        System.out.println("Save meta data");
    }
    
    private void saveComments() {
        progressPane.setVisible(true);
        progressIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        progressLabel.setText("Saving comments...");
        Task<Boolean> taskSaveComments = new Task<>() {
            @Override
            protected Boolean call() throws IOException {
                //saveImageEdits();
                FileInputStream fin;
                ByteArrayOutputStream bout = null;
                try {
                    bout = new ByteArrayOutputStream();
                    fin = new FileInputStream(actualMediaFile.getPathStorage().toFile());
                    
                    Metadata.insertComment(fin, bout, commentText.getText());
                    
                    try ( OutputStream outputStream = new FileOutputStream(actualMediaFile.getPathStorage().toFile())) {
                        bout.writeTo(outputStream);
                    }
                    fin.close();
                } catch (IOException ex) {
                    Logger.getLogger(MetadataController.class.getName()).log(Level.SEVERE, null, ex);
                } finally {
                    try {
                        if (bout != null) {
                            bout.close();
                        }
                    } catch (IOException ex) {
                        Logger.getLogger(MetadataController.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                return null;
            }
        };
        taskSaveComments.setOnScheduled((t) -> {
            progressPane.setVisible(false);
        });
        taskSaveComments.setOnFailed((t) -> {
            progressPane.setVisible(true);
        });
        executor.submit(taskSaveComments);
    }

    /**
     * Saves the keywords to the file
     *
     */
    private void saveKeywordsTitle() {
        progressPane.setVisible(true);
        progressIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        progressLabel.setText("Saving keywords...");
        Task<Boolean> taskKeywordsTitle = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                if (this.isCancelled() == false) {
                    updateKeywordsTitle(captionTextField.getText(), keywordText.getText());
                }
                return null;
            }
        };        
        taskKeywordsTitle.setOnSucceeded((t) -> {
            progressPane.setVisible(false);
            lightController.getTitleLabel().textProperty().unbind();
            lightController.getTitleLabel().setText(captionTextField.getText());
        });
        taskKeywordsTitle.setOnFailed((t) -> {
            progressPane.setVisible(true);
        });
        executor.submit(taskKeywordsTitle);
    }

    /**
     * Saves the keywords to the file
     *
     * @param givenMediaFile if null is specified the actual selected mediafile
     * will be used (var actualMediaFile)
     */
    private void updateKeywordsTitle(String title, String keywords) throws Exception {
        FileInputStream fin;
        ByteArrayOutputStream bout = null;
        try {
            bout = new ByteArrayOutputStream();
            
            fin = new FileInputStream(actualMediaFile.getPathStorage().toFile());
            
            if (iptcdata == null) {
                iptcdata = new IPTC();
            }
            Map<IPTCTag, List<IPTCDataSet>> dataSets = iptcdata.getDataSets();
            List<IPTCDataSet> keywordListLocal = dataSets.get(IPTCApplicationTag.KEY_WORDS);
            if (keywordListLocal == null) {
                keywordListLocal = new ArrayList<>();
                dataSets.put(IPTCApplicationTag.KEY_WORDS, keywordListLocal);
            }
            keywordListLocal.clear();
            
            StringTokenizer defaultTokenizer = new StringTokenizer(keywords, ";");
            while (defaultTokenizer.hasMoreTokens()) {
                keywordListLocal.add(new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, defaultTokenizer.nextToken()));
            }            
            List<IPTCDataSet> objNameList = dataSets.get(IPTCApplicationTag.OBJECT_NAME);            
            List<IPTCDataSet> captionList = dataSets.get(IPTCApplicationTag.CAPTION_ABSTRACT);
            if (captionList == null) {
                captionList = new ArrayList<>();
                dataSets.put(IPTCApplicationTag.CAPTION_ABSTRACT, captionList);                
            }
            if (objNameList != null) {
                objNameList.clear();
            } else {
                objNameList = new ArrayList<>();
                dataSets.put(IPTCApplicationTag.OBJECT_NAME, objNameList);
            }
            objNameList.add(new IPTCDataSet(IPTCApplicationTag.OBJECT_NAME, title));
            if (captionList != null) {
                captionList.clear();
            } else {
                captionList = new ArrayList<>();
                dataSets.put(IPTCApplicationTag.CAPTION_ABSTRACT, captionList);
            }
            captionList.add(new IPTCDataSet(IPTCApplicationTag.CAPTION_ABSTRACT, title));
            
            List<IPTCDataSet> iptcs = new ArrayList<>();
            dataSets.entrySet().forEach((t) -> {
                List<IPTCDataSet> tagValues = t.getValue();
                tagValues.forEach(tagValue -> {
                    iptcs.add(tagValue);
                });
            });
            Metadata.insertIPTC(fin, bout, iptcs, false);            
            try ( OutputStream outputStream = new FileOutputStream(actualMediaFile.getPathStorage().toFile())) {
                bout.writeTo(outputStream);
            }
            fin.close();
        } catch (IOException ex) {
            Logger.getLogger(MetadataController.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            try {
                if (bout != null) {
                    bout.close();
                }
            } catch (IOException ex) {
                Logger.getLogger(MetadataController.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    @FXML
    private void applyKeywordsToAllAction(ActionEvent event) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Apply Caption/Title and keywords to all mediafiles in event\n" + this.actualMediaFile.getPathStorage(), ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("Confirmation");
        alert.setHeaderText("Apply Caption/Title/keywords to all mediafiles");
        DialogPane dialogPane = alert.getDialogPane();
        VBox content = new VBox();
        content.setAlignment(Pos.CENTER_RIGHT);
        content.setSpacing(5);
        HBox titlebox = new HBox();
        titlebox.setSpacing(5);
        titlebox.setAlignment(Pos.TOP_RIGHT);
        Label title = new Label("Title/Caption");
        TextField titleText = new TextField(captionTextField.getText());
        titleText.setPrefWidth(300);
        titlebox.getChildren().add(title);
        titlebox.getChildren().add(titleText);
        content.getChildren().add(titlebox);
        HBox keywordbox = new HBox();
        keywordbox.setSpacing(5);
        keywordbox.setAlignment(Pos.TOP_RIGHT);
        Label keywordLabel = new Label("Keywords");
        TextArea keywordsToAllText = new TextArea(keywordText.getText());
        keywordsToAllText.setPrefSize(300, 100);    
        HBox addKeywordToAllbox = new HBox();
        addKeywordToAllbox.setAlignment(Pos.TOP_RIGHT);
        TextField addKeywordToAllField = new TextField();
        addKeywordToAllField.setPromptText("Add keywords...");
        addKeywordToAllField.setPrefWidth(300);
        addKeywordToAllbox.getChildren().add(addKeywordToAllField);
        addKeywordToAllField.setOnKeyPressed((t) -> {
            if (t.getCode() == KeyCode.ENTER) {
                if (!keywordsToAllText.getText().equalsIgnoreCase("")) {
                    String lastChar = keywordsToAllText.getText().substring(keywordsToAllText.getText().length() - 1);
                    if (!lastChar.equalsIgnoreCase(";")) {
                        keywordsToAllText.appendText(";" + addKeywordToAllField.getText());
                    } else {
                        keywordsToAllText.appendText(addKeywordToAllField.getText());
                    }
                } else {
                    keywordsToAllText.appendText(addKeywordToAllField.getText());
                }
                addKeywordToAllField.clear();
            }
        });
        keywordbox.getChildren().add(keywordLabel);
        keywordbox.getChildren().add(keywordsToAllText);        
        content.getChildren().add(keywordbox);
        content.getChildren().add(addKeywordToAllbox);
        
        dialogPane.setContent(content);
        dialogPane.getStylesheets().add(
                getClass().getResource("/org/photoslide/fxml/Dialogs.css").toExternalForm());
        Utility.centerChildWindowOnStage((Stage)alert.getDialogPane().getScene().getWindow(), (Stage)progressPane.getScene().getWindow()); 
        alert.showAndWait();
        if (alert.getResult() == ButtonType.OK) {
            
            resetGUI();
            mainController.getProgressPane().setVisible(true);
            mainController.getProgressbar().setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            mainController.getProgressbarLabel().setText("Setting metadata...");
            mainController.getStatusLabelLeft().setText("Setting metadata");
            mainController.getStatusLabelLeft().setVisible(true);
            
            Task<Boolean> taskApplyToAll = new Task<>() {
                @Override
                protected Boolean call() throws Exception {

                    // for loop through the media files
                    ObservableList<MediaFile> mediaList = lightController.getList();
                    int i = 0;
                    for (MediaFile actFile : mediaList) {
                        if (this.isCancelled() == false) {
                            updateProgress(i + 1, mediaList.size());
                            updateMessage("" + (i + 1) + "/" + mediaList.size());
                            actualMediaFile = actFile;
                            readBasicMetadata(this);
                            updateKeywordsTitle(titleText.getText(), keywordsToAllText.getText());
                            i++;
                        }
                    }
                    return null;
                }
            };
            taskApplyToAll.setOnSucceeded((t) -> {
                mainController.getProgressbar().progressProperty().unbind();
                mainController.getProgressbarLabel().textProperty().unbind();
                Platform.runLater(() -> {
                    mainController.getProgressbarLabel().setText("");
                    mainController.getProgressPane().setVisible(false);
                    mainController.getStatusLabelLeft().setVisible(false);
                });
                actualMediaFile = lightController.getFactory().getSelectedCell().getItem();
                setSelectedFile(actualMediaFile);
            });
            taskApplyToAll.setOnFailed((t) -> {
                mainController.getProgressbar().progressProperty().unbind();
                mainController.getProgressbarLabel().textProperty().unbind();
                mainController.getProgressPane().setVisible(false);
                mainController.getStatusLabelLeft().setVisible(false);
            });
            mainController.getProgressbar().progressProperty().unbind();
            mainController.getProgressbar().progressProperty().bind(taskApplyToAll.progressProperty());
            mainController.getProgressbarLabel().textProperty().unbind();
            mainController.getProgressbarLabel().textProperty().bind(taskApplyToAll.messageProperty());
            executor.submit(taskApplyToAll);
        }
    }
    
    private class KeywordChangeListener implements ChangeListener<String> {
        
        @Override
        public void changed(ObservableValue<? extends String> ov, String t, String t1) {
            saveKeywordsTitle();
        }
        
    }
    
    private class CommentsChangeListener implements ChangeListener<String> {
        
        @Override
        public void changed(ObservableValue<? extends String> ov, String t, String t1) {
            saveComments();
        }
        
    }
    
    private class CaptionChangeListener implements ChangeListener<String> {
        
        @Override
        public void changed(ObservableValue<? extends String> ov, String t, String t1) {
            saveKeywordsTitle();
        }
        
    }
    
    public void setActualMediaFile(MediaFile actualMediaFile) {
        this.actualMediaFile = actualMediaFile;
    }
    
    public TextField getRecordDateField() {
        return recordDateField;
    }
    
    public void cancelTasks() {
        if (task != null) {
            keywordText.textProperty().removeListener(keywordsChangeListener);
            captionTextField.textProperty().removeListener(captionChangeListener);
            commentText.textProperty().removeListener(commentsChangeListener);
            progressPane.setVisible(false);
            task.cancel();
        }
    }
    
    public void Shutdown() {
        if (task != null) {
            task.cancel();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
