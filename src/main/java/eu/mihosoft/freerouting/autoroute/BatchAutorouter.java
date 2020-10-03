/*
 *   Copyright (C) 2014  Alfons Wirtz
 *   website www.freerouting.net
 *
 *   Copyright (C) 2017 Michael Hoffer <info@michaelhoffer.de>
 *   Website www.freerouting.mihosoft.eu
*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/> 
 *   for more details.
 */
package eu.mihosoft.freerouting.autoroute;

import java.sql.Array;
import java.util.*;

import eu.mihosoft.freerouting.board.*;
import eu.mihosoft.freerouting.datastructures.UndoableObjects;

import eu.mihosoft.freerouting.geometry.planar.FloatLine;

import eu.mihosoft.freerouting.interactive.BoardHandling;
import eu.mihosoft.freerouting.interactive.InteractiveActionThread;
import eu.mihosoft.freerouting.logger.FRLogger;
import eu.mihosoft.freerouting.rules.Net;
import eu.mihosoft.freerouting.rules.Nets;

/**
 * Handles the sequencing of the batch autoroute passes.
 * 
 * @author Alfons Wirtz
 */
public class BatchAutorouter
{
    private HashSet<String> already_checked_board_hashes = new HashSet<String>();

    /**
     *  Autoroutes ripup passes until the board is completed or the autorouter is stopped by the user,
     *  or if p_max_pass_count is exceeded. Is currently used in the optimize via batch pass.
     *  Returns the number of passes to complete the board or p_max_pass_count + 1,
     *  if the board is not completed.
     */
    public static int autoroute_passes_for_optimizing_item(InteractiveActionThread p_thread,
            int p_max_pass_count, int p_ripup_costs, boolean p_with_prefered_directions)
    {
        BatchAutorouter router_instance = new BatchAutorouter(p_thread, true, p_with_prefered_directions, p_ripup_costs);
        boolean still_unrouted_items = true;
        int curr_pass_no = 1;
        while (still_unrouted_items && !router_instance.is_interrupted && curr_pass_no <= p_max_pass_count)
        {
            if (p_thread.is_stop_requested())
            {
                router_instance.is_interrupted = true;
            }
            still_unrouted_items = router_instance.autoroute_pass(curr_pass_no, false);
            if (still_unrouted_items && !router_instance.is_interrupted)
            {
                p_thread.hdlg.get_settings().autoroute_settings.increment_pass_no();
            }
            ++curr_pass_no;
        }
        router_instance.remove_tails(Item.StopConnectionOption.NONE);
        if (!still_unrouted_items)
        {
            --curr_pass_no;
        }
        return curr_pass_no;
    }

    /**
     * Creates a new batch autorouter.
     */
    public BatchAutorouter(InteractiveActionThread p_thread, boolean p_remove_unconnected_vias, boolean p_with_preferred_directions,
            int p_start_ripup_costs)
    {
        this.thread = p_thread;
        this.hdlg = p_thread.hdlg;
        this.routing_board = this.hdlg.get_routing_board();
        this.remove_unconnected_vias = p_remove_unconnected_vias;
        if (p_with_preferred_directions)
        {
            this.trace_cost_arr = this.hdlg.get_settings().autoroute_settings.get_trace_cost_arr();
        }
        else
        {
            // remove preferred direction
            this.trace_cost_arr = new AutorouteControl.ExpansionCostFactor[this.routing_board.get_layer_count()];
            for (int i = 0; i < this.trace_cost_arr.length; ++i)
            {
                double curr_min_cost = this.hdlg.get_settings().autoroute_settings.get_preferred_direction_trace_costs(i);
                this.trace_cost_arr[i] = new AutorouteControl.ExpansionCostFactor(curr_min_cost, curr_min_cost);
            }
        }

        this.start_ripup_costs = p_start_ripup_costs;
        this.retain_autoroute_database = false;
    }

    private LinkedList<Integer> diffBetweenBoards = new LinkedList<Integer>();

    /**
     *  Autoroutes ripup passes until the board is completed or the autorouter is stopped by the user.
     *  Returns true if the board is completed.
     */
    public boolean autoroute_passes()
    {
        java.util.ResourceBundle resources =
                java.util.ResourceBundle.getBundle("eu.mihosoft.freerouting.interactive.InteractiveState", hdlg.get_locale());
        boolean still_unrouted_items = true;
        while (still_unrouted_items && !this.is_interrupted)
        {
            if (thread.is_stop_requested())
            {
                this.is_interrupted = true;
            }

            String current_board_hash = this.routing_board.get_hash();
            if (already_checked_board_hashes.contains(current_board_hash))
            {
                FRLogger.warn("This board was already evaluated, so we stop autorouter to avoid the endless loop.");
                thread.request_stop();
                break;
            }

            Integer curr_pass_no = hdlg.get_settings().autoroute_settings.get_start_pass_no();
            if (curr_pass_no > hdlg.get_settings().autoroute_settings.get_stop_pass_no())
            {
                thread.request_stop();
                break;
            }

            String start_message = resources.getString("batch_autorouter") + " " + resources.getString("stop_message") + "        " + resources.getString("pass") + " " + curr_pass_no.toString() + ": ";
            hdlg.screen_messages.set_status_message(start_message);

            BasicBoard boardBefore = this.routing_board.clone();

            FRLogger.traceEntry("BatchAutorouter.autoroute_pass #"+curr_pass_no+" on board '"+current_board_hash+"' making {} changes");
            already_checked_board_hashes.add(this.routing_board.get_hash());
            still_unrouted_items = autoroute_pass(curr_pass_no, true);

            // let's check if there was enough change in the last pass, because if it were little, so should probably stop
            int newTraceDifferences = this.routing_board.diff_traces(boardBefore);
            diffBetweenBoards.add(newTraceDifferences);

            if (diffBetweenBoards.size() > 20) {
                diffBetweenBoards.removeFirst();

                OptionalDouble average = diffBetweenBoards
                        .stream()
                        .mapToDouble(a -> a)
                        .average();

                if (average.getAsDouble() < 20.0)
                {
                    FRLogger.warn("There were only " + average.getAsDouble() + " changes in the last 20 passes, so it's very likely that autorouter can't improve the result much further. It is recommended to stop it and finish the board manually.");
                }
            }
            FRLogger.traceExit("BatchAutorouter.autoroute_pass #"+curr_pass_no+" on board '"+current_board_hash+"' making {} changes", newTraceDifferences);

            // check if there are still unrouted items
            if (still_unrouted_items && !is_interrupted)
            {
                hdlg.get_settings().autoroute_settings.increment_pass_no();
            }
        }
        if (!(this.remove_unconnected_vias || still_unrouted_items || this.is_interrupted))
        {
            // clean up the route if the board is completed and if fanout is used.
            remove_tails(Item.StopConnectionOption.NONE);
        }

        already_checked_board_hashes.clear();

        return !this.is_interrupted;
    }

    /**
     * Autoroutes one ripup pass of all items of the board.
     * Returns false, if the board is already completely routed.
     */
    private boolean autoroute_pass(int p_pass_no, boolean p_with_screen_message)
    {
        try
        {
            ArrayList<Item>[] autoroute_item_list = new ArrayList[routing_board.rules.nets.max_net_no()];
            Set<Item> handeled_items = new TreeSet<Item>();
            Iterator<UndoableObjects.UndoableObjectNode> it = routing_board.item_list.start_read_object();
            for (;;)
            {
                UndoableObjects.Storable curr_ob = routing_board.item_list.read_object(it);
                if (curr_ob == null)
                {
                    break;
                }
                if (curr_ob instanceof Connectable && curr_ob instanceof Item)
                {
                    Item curr_item = (Item) curr_ob;
                    if (!curr_item.is_route())
                    {
                        if (!handeled_items.contains(curr_item))
                        {
                            for (int i = 0; i < curr_item.net_count(); ++i)
                            {
                                int curr_net_no = curr_item.get_net_no(i);
                                Set<Item> connected_set = curr_item.get_connected_set(curr_net_no);
                                for (Item curr_connected_item : connected_set)
                                {
                                    if (curr_connected_item.net_count() <= 1)
                                    {
                                        handeled_items.add(curr_connected_item);
                                    }
                                }
                                int net_item_count = routing_board.connectable_item_count(curr_net_no);
                                if (connected_set.size() < net_item_count)
                                {
                                    if(autoroute_item_list[curr_net_no-1] == null){
                                        autoroute_item_list[curr_net_no-1] = new ArrayList<>();
                                    }
                                    autoroute_item_list[curr_net_no-1].add(curr_item);
                                }
                            }
                        }
                    }
                }
            }

            ArrayList<ArrayList<Item>> segmented_autoroute_item_list = new ArrayList<>();
            for(ArrayList<Item> list : autoroute_item_list){
                if(list == null){
                    continue;
                }
                segmented_autoroute_item_list.add(list);
            }

            if (segmented_autoroute_item_list.isEmpty())
            {
                this.air_lines.clear();
                return false;
            }

            // Parallelize auto-routing of items
            int NUM_THREADS = 8;
            List<Thread> threads = new ArrayList<>();
            for(int i =0; i < NUM_THREADS ; i++){
                Thread thread = new Thread(new AutorouteItemThread(this, segmented_autoroute_item_list, p_pass_no, p_with_screen_message, i, NUM_THREADS));
                threads.add(thread);
                thread.start();
            }

            for (Thread thread : threads) {
                thread.join();
            }

            if (routing_board.get_test_level() != eu.mihosoft.freerouting.board.TestLevel.ALL_DEBUGGING_OUTPUT)
            {
                Item.StopConnectionOption stop_connection_option;
                if (this.remove_unconnected_vias)
                {
                    stop_connection_option = Item.StopConnectionOption.NONE;
                }
                else
                {
                    stop_connection_option = Item.StopConnectionOption.FANOUT_VIA;
                }
                remove_tails(stop_connection_option);
            }
            this.air_lines.clear();
            return true;
        } catch (Exception e)
        {
            this.air_lines.clear();
            return false;
        }
    }

    private void remove_tails(Item.StopConnectionOption p_stop_connection_option)
    {
        routing_board.start_marking_changed_area();
        routing_board.remove_trace_tails(-1, p_stop_connection_option);
        routing_board.opt_changed_area(new int[0], null, this.hdlg.get_settings().get_trace_pull_tight_accuracy(),
                this.trace_cost_arr, this.thread, TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP);
    }

    public final InteractiveActionThread thread;
    public final BoardHandling hdlg;
    public final RoutingBoard routing_board;
    private boolean is_interrupted = false;
    public final boolean remove_unconnected_vias;
    public final AutorouteControl.ExpansionCostFactor[] trace_cost_arr;
    public final boolean retain_autoroute_database;
    public final int start_ripup_costs;
    /** Used to draw the airline of the current incomplete route. */
    private List<FloatLine> air_lines = new ArrayList<>();
    public static final int TIME_LIMIT_TO_PREVENT_ENDLESS_LOOP = 1000;

    public List<FloatLine> get_air_lines() {
        List<FloatLine> float_lines = new ArrayList<>(air_lines);
        air_lines.clear();
        return float_lines;
    }

    public void add_air_line(FloatLine p_air_line) {
        air_lines.add(p_air_line);
    }

    public boolean is_interrupted() {
        return is_interrupted;
    }

    public void set_is_interrupted(boolean is_interrupted) {
        this.is_interrupted = is_interrupted;
    }

    int items_to_go_count;
    int ripped_item_count;
    int not_found;
    int routed;

    synchronized public void decrementItemsToGoCount() {
        this.items_to_go_count--;
    }

    synchronized public void addRippedItemCount(int ripped_item_count_increase) {
        this.ripped_item_count += ripped_item_count_increase;
    }

    synchronized public void incrementNotFound() {
        this.not_found++;
    }

    synchronized public void incrementRouted() {
        this.routed++;
    }
}
