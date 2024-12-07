import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

record BankRecords(Collection<Owner> owners, Collection<Account> accounts, Collection<RegisterEntry> registerEntries) {
}

public class Obfuscator {
    private static Logger logger = LogManager.getLogger(Obfuscator.class.getName());

    public BankRecords obfuscate(BankRecords rawObjects) {
        List<Owner> obfuscatedOwners = new ArrayList<>();
        for (Owner o : rawObjects.owners()) {

            String maskedSsn = "***-**-" + o.ssn().substring(7);

            String fakeName = "User" + o.id();
            String fakeAddress = "123 Placeholder St";
            String fakeCity = "ObfusCity";
            String fakeState = "XX";
            String fakeZip = "00000";

            obfuscatedOwners.add(new Owner(
                    fakeName,
                    o.id(),
                    o.dob(),
                    maskedSsn,
                    fakeAddress,
                    o.address2(),
                    fakeCity,
                    fakeState,
                    fakeZip));
        }

        List<Account> obfuscatedAccounts = new ArrayList<>();
        Map<Long, Long> accountIdMapping = new HashMap<>();

        for (Account acc : rawObjects.accounts()) {
            Long obfuscatedId = accountIdMapping.computeIfAbsent(acc.getId(), id -> (long) id.hashCode());
            Long obfuscatedOwnerId = accountIdMapping.computeIfAbsent(acc.getOwnerId(), id -> (long) id.hashCode());

            String obfuscatedName = "Account-" + obfuscatedId;

            if (acc instanceof SavingsAccount savings) {
                SavingsAccount obfuscatedSavingsAccount = new SavingsAccount(
                        obfuscatedName,
                        obfuscatedId,
                        savings.getBalance(),
                        savings.getInterestRate(),
                        obfuscatedOwnerId);
                obfuscatedSavingsAccount.setMinimumBalance(savings.getMinimumBalance());
                obfuscatedSavingsAccount.setBelowMinimumFee(savings.getBelowMinimumFee());
                obfuscatedAccounts.add(obfuscatedSavingsAccount);
            } else if (acc instanceof CheckingAccount checking) {
                String details = checking.toString();
                long checkNumber = extractCheckNumberFromString(details);

                CheckingAccount obfuscatedCheckingAccount = new CheckingAccount(
                        obfuscatedName,
                        obfuscatedId,
                        checking.getBalance(),
                        checkNumber,
                        obfuscatedOwnerId);

                obfuscatedCheckingAccount.setMinimumBalance(checking.getMinimumBalance());
                obfuscatedCheckingAccount.setBelowMinimumFee(checking.getBelowMinimumFee());
                obfuscatedAccounts.add(obfuscatedCheckingAccount);
            } else {
                throw new IllegalArgumentException("Unknown account type: " + acc.getClass());
            }
        }

        List<RegisterEntry> obfuscatedRegisterEntries = new ArrayList<>();
        for (RegisterEntry entry : rawObjects.registerEntries()) {
            Long obfuscatedAccountId = accountIdMapping.get(entry.accountId());

            RegisterEntry obfuscatedEntry = new RegisterEntry(
                    entry.getId(),
                    obfuscatedAccountId,
                    entry.entryName(),
                    entry.amount(),
                    entry.date());
            obfuscatedRegisterEntries.add(obfuscatedEntry);
        }

        return new BankRecords(obfuscatedOwners, obfuscatedAccounts, obfuscatedRegisterEntries);
    }

    private long extractCheckNumberFromString(String details) {
        String marker = "Current Check #";
        int index = details.indexOf(marker);
        if (index == -1) {
            throw new RuntimeException("Check number not found in toString output");
        }
        return Long.parseLong(details.substring(index + marker.length()).trim());
    }

    /**
     * Change the integration test suite to point to our obfuscated production
     * records.
     *
     * To use the original integration test suite files run
     * "git checkout -- src/test/resources/persister_integ.properties"
     */
    public void updateIntegProperties() throws IOException {
        Properties props = new Properties();
        File propsFile = new File("src/test/resources/persister_integ.properties".replace('/', File.separatorChar));
        if (!propsFile.exists() || !propsFile.canWrite()) {
            throw new RuntimeException("Properties file must exist and be writable: " + propsFile);
        }
        try (InputStream propsStream = new FileInputStream(propsFile)) {
            props.load(propsStream);
        }
        props.setProperty("persisted.suffix", "_prod");
        logger.info("Updating properties file '{}'", propsFile);
        try (OutputStream propsStream = new FileOutputStream(propsFile)) {
            String comment = String.format(
                    "Note: Don't check in changes to this file!!\n" +
                            "#Modified by %s\n" +
                            "#to reset run 'git checkout -- %s'",
                    this.getClass().getName(), propsFile);
            props.store(propsStream, comment);
        }
    }

    public static void main(String[] args) throws Exception {
        // enable assertions
        Obfuscator.class.getClassLoader().setClassAssertionStatus("Obfuscator", true);
        logger.info("Loading Production Records");
        Persister.setPersisterPropertiesFile("persister_prod.properties");
        Bank bank = new Bank();
        bank.loadAllRecords();

        logger.info("Obfuscating records");
        Obfuscator obfuscator = new Obfuscator();
        // Make a copy of original values so we can compare length
        // deep-copy collections so changes in obfuscator don't impact originals
        BankRecords originalRecords = new BankRecords(
                new ArrayList<>(bank.getAllOwners()),
                new ArrayList<>(bank.getAllAccounts()),
                new ArrayList<>(bank.getAllRegisterEntries()));
        BankRecords obfuscatedRecords = obfuscator.obfuscate(originalRecords);

        logger.info("Saving obfuscated records");
        obfuscator.updateIntegProperties();
        Persister.resetPersistedFileNameAndDir();
        Persister.setPersisterPropertiesFile("persister_integ.properties");
        // old version of file is cached so we need to override prefix (b/c file changed
        // is not the one on classpath)
        Persister.setPersistedFileSuffix("_prod");
        // writeReords is cribbed from Bank.saveALlRecords(), refactor into common
        // method?
        Persister.writeRecordsToCsv(obfuscatedRecords.owners(), "owners");
        Map<Class<? extends Account>, List<Account>> splitAccounts = obfuscatedRecords
                .accounts()
                .stream()
                .collect(Collectors.groupingBy(rec -> rec.getClass()));
        Persister.writeRecordsToCsv(splitAccounts.get(SavingsAccount.class), "savings");
        Persister.writeRecordsToCsv(splitAccounts.get(CheckingAccount.class), "checking");
        Persister.writeRecordsToCsv(obfuscatedRecords.registerEntries(), "register");

        logger.info("Original   record counts: {} owners, {} accounts, {} registers",
                originalRecords.owners().size(),
                originalRecords.accounts().size(),
                originalRecords.registerEntries().size());
        logger.info("Obfuscated record counts: {} owners, {} accounts, {} registers",
                obfuscatedRecords.owners().size(),
                obfuscatedRecords.accounts().size(),
                obfuscatedRecords.registerEntries().size());

        if (obfuscatedRecords.owners().size() != originalRecords.owners().size())
            throw new AssertionError("Owners count mismatch");
        if (obfuscatedRecords.accounts().size() != originalRecords.accounts().size())
            throw new AssertionError("Account count mismatch");
        if (obfuscatedRecords.registerEntries().size() != originalRecords.registerEntries().size())
            throw new AssertionError("RegisterEntries count mismatch");
    }
}
