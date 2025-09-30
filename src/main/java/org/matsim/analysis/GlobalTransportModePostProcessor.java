package org.matsim.analysis;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
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
 * 全局交通模式后处理分析工具
 * 专门用于分析现有事件文件中的所有交通模式使用情况
 * 这是一个独立的后处理工具，不需要集成到MATSim运行中
 */
public class GlobalTransportModePostProcessor implements
        PersonDepartureEventHandler,
        VehicleEntersTrafficEventHandler,
        PersonArrivalEventHandler {

    // 按交通模式统计行程数
    private final Map<String, Integer> modeTripCounts = new HashMap<>();

    // 按交通模式统计行程距离
    private final Map<String, Double> modeTotalDistances = new HashMap<>();

    // 按交通模式统计行程时间
    private final Map<String, Double> modeTotalTravelTimes = new HashMap<>();

    // 存储行程开始时间，用于计算行程时间
    private final Map<Id<Person>, Double> tripStartTimes = new HashMap<>();

    // 存储人员正在使用的交通模式
    private final Map<Id<Person>, String> personCurrentMode = new HashMap<>();

    // 存储车辆对应的交通模式
    private final Map<Id<Vehicle>, String> vehicleToMode = new HashMap<>();

    // 常见交通模式列表，用于分类未知模式
    private final List<String> knownModes = Arrays.asList(
            TransportMode.car, TransportMode.bike, TransportMode.walk,
            TransportMode.pt, TransportMode.truck, TransportMode.ride, "freight");

    // 总行程数
    private int totalTrips = 0;

    // 独立用户数（每个用户只计算一次，不管使用多少种交通模式）
    private final Set<Id<Person>> uniquePersons = new HashSet<>();

    // 用户使用的交通模式集合（一个人可能使用多种交通模式）
    private final Map<Id<Person>, Set<String>> personToModes = new HashMap<>();

    // 添加为成员变量，解决引用问题
    private int singleModeUsers = 0;
    private int multiModeUsers = 0;

    public GlobalTransportModePostProcessor() {
        // 初始化交通模式统计
        for (String mode : knownModes) {
            modeTripCounts.put(mode, 0);
            modeTotalDistances.put(mode, 0.0);
            modeTotalTravelTimes.put(mode, 0.0);
        }
        // 为未知模式添加统计
        modeTripCounts.put("other", 0);
        modeTotalDistances.put("other", 0.0);
        modeTotalTravelTimes.put("other", 0.0);
    }

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        // 记录行程开始，统计交通模式
        String mode = event.getLegMode();

        // 规范交通模式名称
        mode = normalizeMode(mode);

        // 记录此人正在使用的交通模式
        personCurrentMode.put(event.getPersonId(), mode);

        // 记录行程开始时间
        tripStartTimes.put(event.getPersonId(), event.getTime());

        // 增加此模式的行程计数
        modeTripCounts.merge(mode, 1, Integer::sum);

        // 增加总行程数
        totalTrips++;

        // 记录用户使用的交通模式
        uniquePersons.add(event.getPersonId());
        personToModes.computeIfAbsent(event.getPersonId(), k -> new HashSet<>()).add(mode);
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        // 记录车辆对应的交通模式
        String mode = event.getNetworkMode();
        mode = normalizeMode(mode);
        vehicleToMode.put(event.getVehicleId(), mode);
    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {
        // 计算行程时间和距离（如果有）
        Id<Person> personId = event.getPersonId();
        if (tripStartTimes.containsKey(personId) && personCurrentMode.containsKey(personId)) {
            String mode = personCurrentMode.get(personId);
            double tripTime = event.getTime() - tripStartTimes.get(personId);

            // 累加行程时间
            modeTotalTravelTimes.merge(mode, tripTime, Double::sum);

            // 清理数据，为下一次行程做准备
            tripStartTimes.remove(personId);
            personCurrentMode.remove(personId);
        }
    }

    @Override
    public void reset(int iteration) {
        modeTripCounts.replaceAll((k, v) -> 0);
        modeTotalDistances.replaceAll((k, v) -> 0.0);
        modeTotalTravelTimes.replaceAll((k, v) -> 0.0);
        tripStartTimes.clear();
        personCurrentMode.clear();
        vehicleToMode.clear();
        uniquePersons.clear();
        personToModes.clear();
        totalTrips = 0;
        singleModeUsers = 0;
        multiModeUsers = 0;
    }

    /**
     * 规范化交通模式名称
     */
    private String normalizeMode(String mode) {
        if (mode == null) {
            return "other";
        }

        mode = mode.toLowerCase();

        // 检查是否为已知模式
        for (String knownMode : knownModes) {
            if (mode.equals(knownMode) || mode.contains(knownMode)) {
                return knownMode;
            }
        }

        // 处理一些特殊情况
        if (mode.contains("bus") || mode.contains("train") || mode.contains("subway") || mode.contains("transit")) {
            return TransportMode.pt;
        }
        if (mode.contains("passenger")) {
            return TransportMode.ride;
        }

        // 无法识别的模式
        return "other";
    }

    /**
     * 计算单模式和多模式用户数量
     * 将计算移到单独的方法中，以便在不同地方调用
     */
    private void calculateModeUserCounts() {
        singleModeUsers = 0;
        multiModeUsers = 0;

        for (Set<String> modes : personToModes.values()) {
            if (modes.size() == 1) {
                singleModeUsers++;
            } else {
                multiModeUsers++;
            }
        }
    }

    /**
     * 输出分析结果
     */
    public void writeResults(String outputPath) {
        // 首先计算单模式和多模式用户数量
        calculateModeUserCounts();

        // 生成CSV文件
        try (BufferedWriter writer = IOUtils.getBufferedWriter(outputPath + "/globalTransportModes.csv")) {
            // 写入交通模式统计
            writer.write("TransportMode,TripCount,Percentage,AvgTripTime(min)\n");

            for (Map.Entry<String, Integer> entry : modeTripCounts.entrySet()) {
                if (entry.getValue() > 0) {
                    String mode = entry.getKey();
                    int trips = entry.getValue();
                    double percentage = (double) trips / totalTrips * 100.0;

                    // 计算平均行程时间（分钟）
                    double avgTripTime = 0.0;
                    if (trips > 0 && modeTotalTravelTimes.containsKey(mode) && modeTotalTravelTimes.get(mode) > 0) {
                        avgTripTime = modeTotalTravelTimes.get(mode) / trips / 60.0; // 转换为分钟
                    }

                    writer.write(String.format("%s,%d,%.2f%%,%.2f\n",
                            mode, trips, percentage, avgTripTime));
                }
            }

            // 写入总计
            writer.write(String.format("Total,%d,100.00%%,%.2f\n",
                    totalTrips,
                    totalTrips > 0 ? modeTotalTravelTimes.values().stream().mapToDouble(Double::doubleValue).sum() / totalTrips / 60.0 : 0));

            // 添加独立用户统计
            writer.write("\nUniquePersons," + uniquePersons.size() + "\n");

            // 添加多模式用户统计
            writer.write("SingleModeUsers," + singleModeUsers + "\n");
            writer.write("MultiModeUsers," + multiModeUsers + "\n");

        } catch (IOException e) {
            e.printStackTrace();
        }

        // 生成人类可读的摘要文件
        try (BufferedWriter writer = IOUtils.getBufferedWriter(outputPath + "/globalTransportModeSummary.txt")) {
            writer.write("Global Transport Mode Analysis\n");
            writer.write("=============================\n\n");

            writer.write("Total trips: " + totalTrips + "\n");
            writer.write("Unique persons: " + uniquePersons.size() + "\n");
            if (uniquePersons.size() > 0) {
                writer.write(String.format("Average trips per person: %.2f\n\n",
                        (double) totalTrips / uniquePersons.size()));
            }

            writer.write("Transport mode distribution:\n");

            // 按使用频率排序的模式列表
            List<Map.Entry<String, Integer>> sortedModes = modeTripCounts.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());

            for (Map.Entry<String, Integer> entry : sortedModes) {
                String mode = entry.getKey();
                int trips = entry.getValue();
                double percentage = (double) trips / totalTrips * 100.0;

                // 计算平均行程时间
                double avgTripTime = 0.0;
                if (trips > 0 && modeTotalTravelTimes.containsKey(mode) && modeTotalTravelTimes.get(mode) > 0) {
                    avgTripTime = modeTotalTravelTimes.get(mode) / trips / 60.0; // 转换为分钟
                }

                writer.write(String.format("- %s: %d trips (%.2f%%), avg. trip time: %.2f minutes\n",
                        mode, trips, percentage, avgTripTime));
            }

            writer.write("\nUser behavior:\n");
            if (uniquePersons.size() > 0) {
                writer.write(String.format("- Single mode users: %d (%.2f%%)\n",
                        singleModeUsers, 100.0 * singleModeUsers / uniquePersons.size()));
                writer.write(String.format("- Multi-mode users: %d (%.2f%%)\n",
                        multiModeUsers, 100.0 * multiModeUsers / uniquePersons.size()));
            }

            // 分析最常见的多模式组合
            if (multiModeUsers > 0) {
                writer.write("\nCommon mode combinations:\n");

                // 统计模式组合
                Map<Set<String>, Integer> modeCombinations = new HashMap<>();
                for (Set<String> modes : personToModes.values()) {
                    if (modes.size() > 1) {
                        modeCombinations.merge(new HashSet<>(modes), 1, Integer::sum);
                    }
                }

                // 最多显示前5个最常见的组合
                modeCombinations.entrySet().stream()
                    .sorted(Map.Entry.<Set<String>, Integer>comparingByValue().reversed())
                    .limit(5)
                    .forEach(entry -> {
                        try {
                            writer.write(String.format("- %s: %d users\n",
                                    entry.getKey().toString(), entry.getValue()));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
            }

            writer.write("\nConclusions:\n");

            // 自动生成一些分析结论
            if (sortedModes.isEmpty()) {
                writer.write("- No trips were found in this events file. The file might be empty or corrupted.\n");
            } else if (sortedModes.size() == 1) {
                writer.write("- This scenario uses only one transport mode: " + sortedModes.get(0).getKey() + "\n");
                writer.write("- This confirms that there are no other transport modes in the entire simulation.\n");
            } else {
                if (sortedModes.get(0).getValue() / (double)totalTrips > 0.9) {
                    writer.write(String.format("- The dominant transport mode is %s (%.2f%% of all trips)\n",
                            sortedModes.get(0).getKey(),
                            100.0 * sortedModes.get(0).getValue() / totalTrips));
                } else {
                    writer.write("- This scenario uses multiple transport modes with a relatively balanced distribution.\n");
                }
            }

            if (uniquePersons.size() > 0 && multiModeUsers == 0) {
                writer.write("- All users stick to a single transport mode throughout the simulation\n");
            }

            // 添加有关此工具的说明
            writer.write("\nNote: This analysis was performed as a post-processing step on the events file\n");
            writer.write("and reflects the actual transport modes used in the complete simulation.\n");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 主方法 - 用于独立运行后处理分析
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: GlobalTransportModePostProcessor <eventsFile> <outputDirectory>");
            System.exit(1);
        }

        String eventsFile = args[0];
        String outputDir = args[1];

        System.out.println("======================================");
        System.out.println("Global Transport Mode Post-Processor");
        System.out.println("======================================");
        System.out.println("Analyzing events file: " + eventsFile);

        // 创建事件管理器和处理器
        EventsManager eventsManager = EventsUtils.createEventsManager();
        GlobalTransportModePostProcessor processor = new GlobalTransportModePostProcessor();
        eventsManager.addHandler(processor);

        // 读取事件文件
        System.out.println("Reading events file...");
        MatsimEventsReader reader = new MatsimEventsReader(eventsManager);
        reader.readFile(eventsFile);

        // 输出结果
        System.out.println("Writing results to: " + outputDir);
        processor.writeResults(outputDir);
        System.out.println("Post-processing analysis complete!");
        System.out.println("Results written to:");
        System.out.println("- " + outputDir + "/globalTransportModes.csv");
        System.out.println("- " + outputDir + "/globalTransportModeSummary.txt");
    }
}
