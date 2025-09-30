package org.matsim.analysis;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.vehicles.Vehicle;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Post-processing tool for analyzing Altstadt (Old Town) entry vehicle traffic and toll payments in existing events files.
 * Enhanced version: Adds analysis for different vehicle types, fixes counting errors, and extends monitored links, including pedestrian and bicycle entries.
 */
public class AltstadtTrafficPostProcessor implements LinkEnterEventHandler, PersonMoneyEventHandler,
	VehicleEntersTrafficEventHandler, PersonDepartureEventHandler {

	// List of link IDs for motorized vehicle entries to Altstadt (original links)
	private final Set<Id<Link>> motorizedEntryLinks = new HashSet<>(Arrays.asList(
		Id.createLinkId("-27444024"), // Ludwigstr. (West, Mittertor)
		Id.createLinkId("-39691826"), // Donaustr. (South, Donautor)
		Id.createLinkId("-27408737"),   // Niederdörfl (East, Stadtkirche)
		Id.createLinkId("27408754#0") // NoNameStr (East, Stadtkirche)
	));

	// Newly added links (possibly pedestrian and bicycle entries)
	private final Set<Id<Link>> additionalEntryLinks = new HashSet<>(Arrays.asList(
		Id.createLinkId("27426360"),   // New pedestrian/bicycle entry
		Id.createLinkId("27392455"),   // New pedestrian/bicycle entry
		Id.createLinkId("-27456906"), // New pedestrian/bicycle entry
		Id.createLinkId("32731276"),		//Parkplatz P5 Entry
		Id.createLinkId("-219468968#3"),	//Parkplatz P5 Entry
		Id.createLinkId("167291419"),		//Parkplatz P5 Entry
		Id.createLinkId("-620652854#2"),	//Parkplatz P5 Entry
		Id.createLinkId("-95355879#1")		//Parkplatz Niederdoerfl
	));

	// Combined set of all entry links
	private final Set<Id<Link>> allEntryLinks = new HashSet<>();

	// Data structures for storing traffic flow
	private final Map<Id<Link>, Integer> linkId2Count = new HashMap<>();
	private final Map<Integer, Integer> hourlyTraffic = new HashMap<>();

	// Three different counting methods
	private final Set<Id<Vehicle>> processedVehicles = new HashSet<>(); // Avoid counting the same vehicle multiple times
	private int totalEntries = 0; // Counts all entry events

	// Toll statistics
	private double totalTollAmount = 0.0;
	private int tollPayments = 0;

	// Statistics for vehicles entering the Old Town multiple times
	private final Map<Id<Vehicle>, Integer> vehicleEntryCount = new HashMap<>();

	// Vehicle type analysis - only used in separate statistics, does not affect original counts
	private final Map<String, Integer> modeEntryCounts = new HashMap<>();  // Entry counts by transport mode
	private final Map<Id<Vehicle>, String> vehicleToMode = new HashMap<>(); // Mapping from Vehicle ID to transport mode
	private final Map<Id<Person>, String> personToMode = new HashMap<>();  // Mapping from Person ID to transport mode

	// Transport mode counts per link
	private final Map<Id<Link>, Map<String, Integer>> linkModeCounts = new HashMap<>();

	// Toll payment statistics by transport mode
	private final Map<String, Integer> modeToTollPayments = new HashMap<>();
	private final Map<String, Double> modeToTollAmount = new HashMap<>();

	// List of common transport modes
	private final List<String> knownModes = Arrays.asList(
		TransportMode.car, TransportMode.bike, TransportMode.walk,
		TransportMode.pt, TransportMode.truck, TransportMode.ride, "freight");

	// Allowed modes for each link (if known)
	private final Map<Id<Link>, Set<String>> linkAllowedModes = new HashMap<>();

	public AltstadtTrafficPostProcessor() {
		// Combine all monitored links
		allEntryLinks.addAll(motorizedEntryLinks);
		allEntryLinks.addAll(additionalEntryLinks);

		// Initialize data structures
		for (Id<Link> linkId : allEntryLinks) {
			linkId2Count.put(linkId, 0);
			linkModeCounts.put(linkId, new HashMap<>());

			// Initialize transport mode counts for each link
			Map<String, Integer> modeCounts = linkModeCounts.get(linkId);
			for (String mode : knownModes) {
				modeCounts.put(mode, 0);
			}
			modeCounts.put("other", 0);

			// Set default allowed modes for each link (based on known information)
			Set<String> allowedModes = new HashSet<>();
			if (motorizedEntryLinks.contains(linkId)) {
				allowedModes.add(TransportMode.car);
				allowedModes.add(TransportMode.ride);
				allowedModes.add(TransportMode.truck);
				allowedModes.add("freight");
			}
			if (additionalEntryLinks.contains(linkId)) {
				allowedModes.add(TransportMode.walk);
				allowedModes.add(TransportMode.bike);
				// Some links might also allow public transport
				allowedModes.add(TransportMode.pt);
			}
			linkAllowedModes.put(linkId, allowedModes);
		}

		// Initialize hourly statistics
		for (int hour = 0; hour < 24; hour++) {
			hourlyTraffic.put(hour, 0);
		}

		// Initialize transport mode statistics
		for (String mode : knownModes) {
			modeEntryCounts.put(mode, 0);
			modeToTollPayments.put(mode, 0);
			modeToTollAmount.put(mode, 0.0);
		}
		modeEntryCounts.put("other", 0); // Other unknown modes
		modeToTollPayments.put("other", 0);
		modeToTollAmount.put("other", 0.0);
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		// Record the person's transport mode, but do not count entries
		personToMode.put(event.getPersonId(), event.getLegMode());
	}

	@Override
	public void handleEvent(VehicleEntersTrafficEvent event) {
		// Only record the vehicle's transport mode, do not count entries
		vehicleToMode.put(event.getVehicleId(), event.getNetworkMode());
	}

	@Override
	public void handleEvent(PersonMoneyEvent event) {
		if (event.getAmount() < 0 && event.getPurpose().equals("toll")) {
			// Record toll payment event
			tollPayments++;
			double amount = -event.getAmount(); // Convert to positive value
			totalTollAmount += amount;

			// Record toll payment by transport mode (separate statistics)
			String mode = getPersonMode(event.getPersonId());
			modeToTollPayments.merge(mode, 1, Integer::sum);
			modeToTollAmount.merge(mode, amount, Double::sum);
		}
	}

	@Override
	public void handleEvent(LinkEnterEvent event) {
		Id<Link> linkId = event.getLinkId();

		// Check if it is any of the monitored entry links
		if (allEntryLinks.contains(linkId)) {
			Id<Vehicle> vehicleId = event.getVehicleId();

			// Get transport mode
			String mode = getVehicleMode(vehicleId);

			// Count total entry events (including duplicates)
			totalEntries++;

			// Update this vehicle's entry count
			vehicleEntryCount.merge(vehicleId, 1, Integer::sum);

			// Count entries by mode
			modeEntryCounts.merge(mode, 1, Integer::sum);

			// Update link's mode count
			Map<String, Integer> modeCounts = linkModeCounts.get(linkId);
			modeCounts.merge(mode, 1, Integer::sum);

			// If this is the first time seeing this vehicle, record it
			if (!processedVehicles.contains(vehicleId)) {
				processedVehicles.add(vehicleId);

				// Update link count (This only counts the first entry of each vehicle)
				linkId2Count.merge(linkId, 1, Integer::sum);

				// Count by hour
				int hour = (int) (event.getTime() / 3600);
				if (hour >= 24) hour = 23; // Times beyond one day are grouped into the last hour
				hourlyTraffic.merge(hour, 1, Integer::sum);
			}
		}
	}

	@Override
	public void reset(int iteration) {
		linkId2Count.replaceAll((k, v) -> 0);
		hourlyTraffic.replaceAll((k, v) -> 0);
		processedVehicles.clear();
		vehicleEntryCount.clear();
		totalEntries = 0;
		totalTollAmount = 0;
		tollPayments = 0;

		vehicleToMode.clear();
		personToMode.clear();
		modeEntryCounts.replaceAll((k, v) -> 0);
		modeToTollPayments.replaceAll((k, v) -> 0);
		modeToTollAmount.replaceAll((k, v) -> 0.0);

		// Reset link mode counts
		for (Map<String, Integer> modeCounts : linkModeCounts.values()) {
			modeCounts.replaceAll((k, v) -> 0);
		}
	}

	/**
	 * Gets the transport mode of the vehicle
	 */
	private String getVehicleMode(Id<Vehicle> vehicleId) {
		// First check for a direct mapping
		if (vehicleToMode.containsKey(vehicleId)) {
			return vehicleToMode.get(vehicleId);
		}

		// Try to infer the mode from the vehicle ID (common naming patterns)
		String vehicleIdStr = vehicleId.toString();
		for (String mode : knownModes) {
			if (vehicleIdStr.contains(mode)) {
				return mode;
			}
		}

		// If the ID contains a person ID, try to get the person's mode
		if (vehicleIdStr.contains("_")) {
			String personIdStr = vehicleIdStr.substring(0, vehicleIdStr.lastIndexOf("_"));
			Id<Person> personId = Id.createPersonId(personIdStr);
			if (personToMode.containsKey(personId)) {
				return personToMode.get(personId);
			}
		}

		// Default to "car", but add new logic
		// If entering from a pedestrian/bicycle link, the default is 'walk' instead of 'car'
		// NOTE: This logic might be flawed as the LinkEnterEvent only provides vehicleId, not linkId.
		// The check below is using vehicleId.toString() as a linkId which is incorrect.
		// For a proper check, the LinkEnterEvent is required. For now, it defaults to 'car'.
        /*
        if (additionalEntryLinks.contains(vehicleId.toString())) {
            return TransportMode.walk;
        }
        */

		return TransportMode.car;
	}

	/**
	 * Gets the transport mode of the person
	 */
	private String getPersonMode(Id<Person> personId) {
		// Check if the person's mode is directly recorded
		if (personToMode.containsKey(personId)) {
			return personToMode.get(personId);
		}

		// Try to infer the mode from the person ID (common naming patterns)
		String personIdStr = personId.toString();
		for (String mode : knownModes) {
			if (personIdStr.contains(mode)) {
				return mode;
			}
		}

		// Default to "car"
		return TransportMode.car;
	}

	/**
	 * Writes the analysis results to a file
	 */
	public void writeResults(String outputPath) {
		// Calculate total traffic (unique vehicles)
		int uniqueVehicles = processedVehicles.size();

		// Calculate multiple entry statistics
		int vehiclesEnteringMultipleTimes = 0;
		int maxEntriesPerVehicle = 0;
		for (Map.Entry<Id<Vehicle>, Integer> entry : vehicleEntryCount.entrySet()) {
			if (entry.getValue() > 1) {
				vehiclesEnteringMultipleTimes++;
			}
			maxEntriesPerVehicle = Math.max(maxEntriesPerVehicle, entry.getValue());
		}

		// Output to CSV file
		try (BufferedWriter writer = IOUtils.getBufferedWriter(outputPath + "/altstadtTraffic.csv")) {
			// Write link-level statistics
			writer.write("LinkId,Description,Type,VehicleCount\n");
			for (Map.Entry<Id<Link>, Integer> entry : linkId2Count.entrySet()) {
				String linkType = motorizedEntryLinks.contains(entry.getKey()) ? "Motorized" : "NonMotorized";
				writer.write(entry.getKey() + "," + getLinkDescription(entry.getKey()) + ","
					+ linkType + "," + entry.getValue() + "\n");
			}
			writer.write("\n");

			// Write link and transport mode cross-statistics
			writer.write("LinkId,Description,Type");
			for (String mode : knownModes) {
				writer.write("," + mode);
			}
			writer.write(",other\n");

			for (Id<Link> linkId : allEntryLinks) {
				String linkType = motorizedEntryLinks.contains(linkId) ? "Motorized" : "NonMotorized";
				writer.write(linkId + "," + getLinkDescription(linkId) + "," + linkType);

				Map<String, Integer> modeCounts = linkModeCounts.get(linkId);
				for (String mode : knownModes) {
					writer.write("," + modeCounts.getOrDefault(mode, 0));
				}
				writer.write("," + modeCounts.getOrDefault("other", 0) + "\n");
			}
			writer.write("\n");

			// Write hourly traffic counts
			writer.write("Hour,VehicleCount\n");
			for (int hour = 0; hour < 24; hour++) {
				writer.write(hour + "," + hourlyTraffic.get(hour) + "\n");
			}
			writer.write("\n");

			// Write comparison of multiple counting methods
			writer.write("CountMethod,Count\n");
			writer.write("UniqueVehicles," + uniqueVehicles + "\n");
			writer.write("TotalEntries," + totalEntries + "\n");
			writer.write("\n");

			// Write toll payment statistics
			writer.write("TollStatistics,Value\n");
			writer.write("TollPayments," + tollPayments + "\n");
			writer.write("TotalTollAmount," + totalTollAmount + "\n");
			writer.write("\n");

			// Write entry counts by transport mode
			writer.write("TransportMode,EntryCount,Percentage\n");
			for (Map.Entry<String, Integer> entry : modeEntryCounts.entrySet()) {
				double percentage = totalEntries > 0 ? 100.0 * entry.getValue() / totalEntries : 0;
				writer.write(entry.getKey() + "," + entry.getValue() + "," + String.format("%.2f%%", percentage) + "\n");
			}
			writer.write("\n");

			// Write toll payments by transport mode
			writer.write("TransportMode,TollPayments,TollAmount,PercentageOfPayments,PercentageOfAmount\n");
			for (String mode : modeEntryCounts.keySet()) {
				int payments = modeToTollPayments.getOrDefault(mode, 0);
				double amount = modeToTollAmount.getOrDefault(mode, 0.0);
				double paymentPercentage = tollPayments > 0 ? 100.0 * payments / tollPayments : 0;
				double amountPercentage = totalTollAmount > 0 ? 100.0 * amount / totalTollAmount : 0;

				writer.write(mode + "," + payments + "," + amount + "," +
					String.format("%.2f%%", paymentPercentage) + "," +
					String.format("%.2f%%", amountPercentage) + "\n");
			}
			writer.write("\n");

			// Write comparison of theoretical vs. actual toll collection
			double expectedTollFromUniqueVehicles = uniqueVehicles * 5.0; // Assuming 5.0 per vehicle
			double expectedTollFromTotalEntries = totalEntries * 5.0;
			writer.write("TollComparison,Value\n");
			writer.write("ExpectedTollFromUniqueVehicles," + expectedTollFromUniqueVehicles + "\n");
			writer.write("ExpectedTollFromTotalEntries," + expectedTollFromTotalEntries + "\n");
			writer.write("ActualTollCollected," + totalTollAmount + "\n");

		} catch (IOException e) {
			e.printStackTrace();
		}

		// Output a human-readable summary file
		try (BufferedWriter writer = IOUtils.getBufferedWriter(outputPath + "/altstadtTrafficSummary.txt")) {
			writer.write("Altstadt Traffic Monitor Summary\n");
			writer.write("===============================\n\n");
			writer.write("Unique vehicles entering Altstadt: " + uniqueVehicles + "\n");
			writer.write("Total entry events: " + totalEntries + "\n");
			writer.write("Vehicles entering multiple times: " + vehiclesEnteringMultipleTimes + "\n");
			writer.write("Maximum entries per vehicle: " + maxEntriesPerVehicle + "\n\n");

			// Separate statistics by entry type
			int motorizedEntries = 0;
			int nonMotorizedEntries = 0;

			for (Id<Link> linkId : motorizedEntryLinks) {
				motorizedEntries += linkId2Count.get(linkId);
			}

			for (Id<Link> linkId : additionalEntryLinks) {
				nonMotorizedEntries += linkId2Count.get(linkId);
			}

			writer.write("Entry statistics by type (unique vehicles):\n");
			writer.write("- Motorized vehicle entries: " + motorizedEntries + "\n");
			writer.write("- Non-motorized/pedestrian entries: " + nonMotorizedEntries + "\n\n");

			writer.write("Entry points (unique vehicles):\n");
			writer.write("A. Motorized vehicle entry points:\n");
			for (Id<Link> linkId : motorizedEntryLinks) {
				writer.write("- " + getLinkDescription(linkId) + " (" + linkId + "): " + linkId2Count.get(linkId) + " vehicles\n");

				// Add transport mode distribution for this entry point
				Map<String, Integer> modeCounts = linkModeCounts.get(linkId);
				List<Map.Entry<String, Integer>> sortedModeForLink = modeCounts.entrySet().stream()
					.filter(e -> e.getValue() > 0)
					.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
					.collect(Collectors.toList());

				if (!sortedModeForLink.isEmpty()) {
					writer.write("  Transport modes (Total Entries): ");
					boolean first = true;
					for (Map.Entry<String, Integer> modeEntry : sortedModeForLink) {
						if (!first) writer.write(", ");
						writer.write(modeEntry.getKey() + " (" + modeEntry.getValue() + ")");
						first = false;
					}
					writer.write("\n");
				}
			}

			writer.write("\nB. Additional entry points (unique vehicles):\n");
			for (Id<Link> linkId : additionalEntryLinks) {
				writer.write("- " + getLinkDescription(linkId) + " (" + linkId + "): " + linkId2Count.get(linkId) + " entries\n");

				// Add transport mode distribution for this entry point
				Map<String, Integer> modeCounts = linkModeCounts.get(linkId);
				List<Map.Entry<String, Integer>> sortedModeForLink = modeCounts.entrySet().stream()
					.filter(e -> e.getValue() > 0)
					.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
					.collect(Collectors.toList());

				if (!sortedModeForLink.isEmpty()) {
					writer.write("  Transport modes (Total Entries): ");
					boolean first = true;
					for (Map.Entry<String, Integer> modeEntry : sortedModeForLink) {
						if (!first) writer.write(", ");
						writer.write(modeEntry.getKey() + " (" + modeEntry.getValue() + ")");
						first = false;
					}
					writer.write("\n");
				}
			}
			writer.write("\n");

			// Add overall transport mode distribution
			writer.write("Overall transport mode distribution (Total Entries):\n");
			List<Map.Entry<String, Integer>> sortedModes = modeEntryCounts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.filter(e -> e.getValue() > 0) // Only display modes with data
				.collect(Collectors.toList());

			for (Map.Entry<String, Integer> entry : sortedModes) {
				double percentage = 100.0 * entry.getValue() / totalEntries;
				writer.write(String.format("- %s: %d entries (%.2f%%)\n",
					entry.getKey(), entry.getValue(), percentage));
			}
			writer.write("\n");

			// Add toll collection statistics
			writer.write("Toll collection statistics:\n");
			writer.write("- Total toll payments: " + tollPayments + "\n");
			writer.write("- Total amount collected: " + totalTollAmount + "\n\n");

			// Add toll collection by transport mode
			writer.write("Toll by transport mode:\n");
			List<Map.Entry<String, Double>> sortedTolls = modeToTollAmount.entrySet().stream()
				.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
				.filter(e -> e.getValue() > 0) // Only display modes with data
				.collect(Collectors.toList());

			for (Map.Entry<String, Double> entry : sortedTolls) {
				int payments = modeToTollPayments.get(entry.getKey());
				double percentage = totalTollAmount > 0 ? 100.0 * entry.getValue() / totalTollAmount : 0;
				writer.write(String.format("- %s: %.1f amount from %d payments (%.2f%%)\n",
					entry.getKey(), entry.getValue(), payments, percentage));
			}
			writer.write("\n");


			// Add peak hour information
			int maxVehicles = hourlyTraffic.values().stream().mapToInt(Integer::intValue).max().orElse(0);
			List<Integer> peakHours = hourlyTraffic.entrySet().stream()
				.filter(e -> e.getValue() == maxVehicles)
				.map(Map.Entry::getKey)
				.collect(Collectors.toList());

			writer.write("Peak hour(s): " + peakHours + " with " + maxVehicles + " unique vehicles\n\n");

			writer.write("Hourly distribution (unique vehicles):\n");
			for (int hour = 0; hour < 24; hour++) {
				writer.write(String.format("%02d:00-%02d:00: %d vehicles\n",
					hour, (hour + 1) % 24, hourlyTraffic.get(hour)));
			}

			// Add validation information to confirm no double counting
			writer.write("\nValidation (Total Entries):\n");
			writer.write(String.format("- Sum of mode-specific entries: %d\n",
				modeEntryCounts.values().stream().mapToInt(Integer::intValue).sum()));
			writer.write(String.format("- Total entries recorded: %d\n", totalEntries));

			// Add notes on new entry points
			writer.write("\nNote: This analysis now includes both motorized vehicle entry points\n");
			writer.write("and pedestrian/bicycle entry points to provide a complete view of all traffic\n");
			writer.write("entering the Altstadt area.\n");

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// Helper method: Get description based on Link ID
	private String getLinkDescription(Id<Link> linkId) {
		String id = linkId.toString();

		// Original vehicle entries
		if (id.equals("-27444024")) return "Ludwigstr. (West, Mittertor)";
		if (id.equals("-39691826")) return "Donaustr. (South, Donautor)";
		if (id.equals("-27408737")) return "Niederdoerfl (East, Stadtkirche)";
		if (id.equals("27408754#0")) return "NoNameStr (East, Stadtkirche)";

		// New entries
		if (id.equals("27426360")) return "southern Pedestrian1 Entry";
		if (id.equals("27392455")) return "southern Pedestrian2 Entry";
		if (id.equals("-27456906")) return "eastern Pedestrian Entry";
		if (id.equals("32731276")) return "Parkplatz P5 Entry";
		if (id.equals("-219468968#3")) return "Parkplatz P5 Entry";
		if (id.equals("167291419")) return "Parkplatz P5 Entry";
		if (id.equals("-620652854#2")) return "Parkplatz P5 Entry";
		if (id.equals("-95355879#1")) return "Parkplatz Niederdoerfl Entry";

		return "Unknown Entry Point";
	}

	/**
	 * Main method to run the post-processing directly
	 */
	public static void main(String[] args) {
		if (args.length < 2) {
			System.err.println("Usage: AltstadtTrafficPostProcessor <eventsFile> <outputDirectory>");
			System.exit(1);
		}

		String eventsFile = args[0];
		String outputDir = args[1];

		// Create events manager and processor
		EventsManager eventsManager = EventsUtils.createEventsManager();
		AltstadtTrafficPostProcessor processor = new AltstadtTrafficPostProcessor();
		eventsManager.addHandler(processor);

		// Read events file
		System.out.println("Reading events file: " + eventsFile);
		MatsimEventsReader reader = new MatsimEventsReader(eventsManager);
		reader.readFile(eventsFile);

		// Output results
		System.out.println("Writing results to: " + outputDir);
		processor.writeResults(outputDir);
		System.out.println("Analysis complete!");
	}
}
