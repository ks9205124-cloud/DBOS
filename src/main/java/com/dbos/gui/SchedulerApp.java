package com.dbos.gui;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.Job;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobExecutionException;
import com.dbos.engine.ExecutionListener;
import com.dbos.engine.SchedulerEngine;
import com.dbos.engine.WorkerPool;
import com.dbos.retry.ExponentialBackoffPolicy;
import com.dbos.retry.FixedDelayPolicy;
import com.dbos.retry.RetryPolicy;
import com.dbos.store.InMemoryJobStore;
import com.dbos.trigger.FixedRateTrigger;
import com.dbos.trigger.OneTimeTrigger;
import com.dbos.trigger.Trigger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Swing front end for the scheduler.
 *
 * A pool of worker threads processes due runs in the background, so a slow
 * job only occupies one worker and the others keep going (the Worker column
 * shows which worker took each run). The window only reads from the store and
 * calls register/cancel. The store is synchronized and hands out copies, so
 * the Swing thread and the workers never share mutable state.
 */
public class SchedulerApp extends JFrame {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int WORKER_COUNT = 4;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final SchedulerEngine engine;
    private final WorkerPool pool;

    // registration form
    private final JTextField nameField = new JTextField("my-job", 12);
    private final JComboBox<String> jobType = new JComboBox<>(
            new String[]{"Cleanup (always succeeds)", "Slow (takes 3s)", "Flaky (fails twice)"});
    private final JComboBox<String> triggerType = new JComboBox<>(
            new String[]{"Fixed rate every", "One-time in"});
    private final JSpinner triggerSeconds = new JSpinner(new SpinnerNumberModel(5, 1, 3600, 1));
    private final JComboBox<String> retryType = new JComboBox<>(
            new String[]{"No retry", "Fixed delay", "Exponential backoff"});
    private final JSpinner maxRetries = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
    private final JSpinner retryDelay = new JSpinner(new SpinnerNumberModel(2, 1, 600, 1));

    // tables and log
    private final DefaultTableModel jobsModel = readOnlyModel("ID", "Name", "Active", "Runs", "Last status");
    private final DefaultTableModel runsModel =
            readOnlyModel("Run", "Attempt", "Status", "Scheduled", "Started", "Ended", "Worker");
    private final JTable jobsTable = new JTable(jobsModel);
    private final JTable runsTable = new JTable(runsModel);
    private final JTextArea log = new JTextArea();

    private boolean refreshing;   // true while the jobs table is being rebuilt

    public SchedulerApp() {
        super("DBOS Job Scheduler");
        Clock clock = Clock.systemUTC();
        LogListener listener = new LogListener();
        this.engine = new SchedulerEngine(new InMemoryJobStore(clock), clock, listener);
        this.pool = new WorkerPool(engine, WORKER_COUNT, POLL_INTERVAL, listener, null);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        jobsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jobsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !refreshing) {
                refreshRuns(selectedJobId());
            }
        });
        log.setEditable(false);

        JSplitPane tables = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled(new JScrollPane(jobsTable), "Jobs (select one to see its runs)"),
                titled(new JScrollPane(runsTable), "Runs of the selected job"));
        tables.setResizeWeight(0.5);

        JScrollPane logPane = titled(new JScrollPane(log), "Events");
        logPane.setPreferredSize(new Dimension(900, 150));

        add(buildForm(), BorderLayout.NORTH);
        add(tables, BorderLayout.CENTER);
        add(logPane, BorderLayout.SOUTH);

        // the workers run off the Swing thread, so a slow job cannot freeze the window
        pool.start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // let running jobs finish briefly; EXIT_ON_CLOSE then ends the program
                pool.stop(Duration.ofSeconds(1));
            }
        });

        new javax.swing.Timer(500, e -> refresh()).start();
    }

    // ---------- form ----------

    private JPanel buildForm() {
        JButton register = new JButton("Register job");
        register.addActionListener(e -> registerJob());
        JButton cancel = new JButton("Cancel selected job");
        cancel.addActionListener(e -> cancelSelected());

        maxRetries.setEnabled(false);
        retryDelay.setEnabled(false);
        retryType.addActionListener(e -> {
            boolean retrying = retryType.getSelectedIndex() != 0;
            maxRetries.setEnabled(retrying);
            retryDelay.setEnabled(retrying);
        });

        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        form.setBorder(BorderFactory.createTitledBorder("Register a job"));
        form.add(new JLabel("Name"));
        form.add(nameField);
        form.add(new JLabel("Job"));
        form.add(jobType);
        form.add(triggerType);
        form.add(triggerSeconds);
        form.add(new JLabel("seconds"));
        form.add(new JLabel("Retry"));
        form.add(retryType);
        form.add(new JLabel("max"));
        form.add(maxRetries);
        form.add(new JLabel("delay (s)"));
        form.add(retryDelay);
        form.add(register);
        form.add(cancel);
        return form;
    }

    private void registerJob() {
        try {
            int seconds = (Integer) triggerSeconds.getValue();
            Trigger trigger = (triggerType.getSelectedIndex() == 0)
                    ? new FixedRateTrigger(Duration.ofSeconds(seconds))
                    : new OneTimeTrigger(Duration.ofSeconds(seconds));

            JobDefinition.JobDefinitionBuilder builder = JobDefinition.builder(nameField.getText())
                    .job(newJob(jobType.getSelectedIndex()))
                    .trigger(trigger);
            RetryPolicy policy = newRetryPolicy();
            if (policy != null) {
                builder.retryPolicy(policy);
            }

            engine.register(builder.build());
            refresh();
        } catch (DuplicateJobException e) {
            showError("A job with that name already exists.");
        } catch (RuntimeException e) {
            showError(e.getMessage());
        }
    }

    private void cancelSelected() {
        long id = selectedJobId();
        if (id < 0) {
            showError("Select a job in the table first.");
            return;
        }
        try {
            engine.cancel(id);
            refresh();
        } catch (RuntimeException e) {
            showError(e.getMessage());
        }
    }

    // a fresh Job per registration, so every flaky job has its own call counter
    private static Job newJob(int type) {
        if (type == 1) {
            return () -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JobExecutionException("interrupted", e);
                }
            };
        }
        if (type == 2) {
            AtomicInteger calls = new AtomicInteger();
            return () -> {
                if (calls.incrementAndGet() <= 2) {
                    throw new JobExecutionException("source unavailable (call " + calls.get() + ")");
                }
            };
        }
        return () -> { };
    }

    // null means "no retry"; the builder then supplies a policy that never retries
    private RetryPolicy newRetryPolicy() {
        int max = (Integer) maxRetries.getValue();
        Duration delay = Duration.ofSeconds((Integer) retryDelay.getValue());
        return switch (retryType.getSelectedIndex()) {
            case 1 -> new FixedDelayPolicy(max, delay);
            case 2 -> new ExponentialBackoffPolicy(max, delay);
            default -> null;
        };
    }

    // ---------- refreshing the tables ----------

    private void refresh() {
        refreshing = true;
        try {
            long selectedId = selectedJobId();
            jobsModel.setRowCount(0);
            int rowToSelect = -1;
            for (JobDefinition job : engine.listJobs()) {
                List<JobExecution> runs = engine.listExecutions(job.getId());
                String last = runs.isEmpty() ? "-" : runs.get(runs.size() - 1).getStatus().toString();
                jobsModel.addRow(new Object[]{
                        job.getId(), job.getName(), job.isActive() ? "yes" : "no", runs.size(), last});
                if (job.getId() == selectedId) {
                    rowToSelect = jobsModel.getRowCount() - 1;
                }
            }
            if (rowToSelect >= 0) {
                jobsTable.setRowSelectionInterval(rowToSelect, rowToSelect);
            }
            refreshRuns(rowToSelect >= 0 ? selectedId : -1);
        } finally {
            refreshing = false;
        }
    }

    private void refreshRuns(long jobId) {
        runsModel.setRowCount(0);
        if (jobId < 0) {
            return;
        }
        for (JobExecution run : engine.listExecutions(jobId)) {
            runsModel.addRow(new Object[]{
                    run.getId(),
                    run.getAttemptNumber(),
                    run.getStatus(),
                    TIME.format(run.getScheduledFor()),
                    run.getStartedAt().map(TIME::format).orElse("-"),
                    run.getEndedAt().map(TIME::format).orElse("-"),
                    run.getWorkerId().map(String::valueOf).orElse("-")});
        }
    }

    private long selectedJobId() {
        int row = jobsTable.getSelectedRow();
        if (row < 0) {
            return -1;
        }
        return (Long) jobsModel.getValueAt(row, 0);
    }

    // ---------- helpers ----------

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Cannot do that", JOptionPane.WARNING_MESSAGE);
    }

    private static <T extends JComponent> T titled(T component, String title) {
        component.setBorder(BorderFactory.createTitledBorder(title));
        return component;
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    // writes the engine's events into the log pane (called from the worker threads)
    private class LogListener implements ExecutionListener {

        void message(String text) {
            String line = TIME.format(Instant.now()) + "  " + text + "\n";
            SwingUtilities.invokeLater(() -> {
                log.append(line);
                log.setCaretPosition(log.getDocument().getLength());
            });
        }

        @Override
        public void onJobRegistered(JobDefinition job) {
            message("registered '" + job.getName() + "'");
        }

        @Override
        public void onRunScheduled(JobDefinition job, JobExecution run) {
            message("scheduled '" + job.getName() + "' attempt " + run.getAttemptNumber()
                    + " for " + TIME.format(run.getScheduledFor()));
        }

        @Override
        public void onRunStarted(JobDefinition job, JobExecution run) {
            message("started   '" + job.getName() + "' attempt " + run.getAttemptNumber()
                    + " on worker " + run.getWorkerId().map(String::valueOf).orElse("?"));
        }

        @Override
        public void onRunSucceeded(JobDefinition job, JobExecution run) {
            message("DONE      '" + job.getName() + "' attempt " + run.getAttemptNumber());
        }

        @Override
        public void onRunFailed(JobDefinition job, JobExecution run, Throwable cause) {
            message("FAILED    '" + job.getName() + "' attempt " + run.getAttemptNumber()
                    + ": " + cause.getMessage());
        }

        @Override
        public void onScheduleEnded(JobDefinition job) {
            message("schedule ended for '" + job.getName() + "'");
        }

        @Override
        public void onWorkerError(long workerId, Throwable problem) {
            message("worker " + workerId + " error: " + problem);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default look and feel is fine
            }
            SchedulerApp app = new SchedulerApp();
            app.setSize(1000, 700);
            app.setLocationRelativeTo(null);
            app.setVisible(true);
        });
    }
}